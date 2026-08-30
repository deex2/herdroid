use std::{
    collections::{BTreeMap, BTreeSet},
    io::{self, BufReader, Read, Write},
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TrySendError},
    },
    thread,
    time::{Duration, Instant},
};

use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    herdr::{API_CALL_TIMEOUT, api_response_until},
    ipc::ApiEndpoint,
    monitor::{MonitorCommand, MonitorCommandSender, MonitorOutput, SessionMonitor},
    protocol::{
        ClientMessage, MAX_LINE_BYTES, ServerMessage, SessionDescriptor, read_client_message,
        write_server_message,
    },
};

const DISCOVERY_INTERVAL: Duration = Duration::from_secs(5);
const CHILD_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_CHILD_STDOUT_BYTES: usize = 1_048_576;
const RUNTIME_EVENT_CAPACITY: usize = 128;
pub const MAX_LIVE_AGENT_SUBSCRIPTIONS: usize = 8;

enum RuntimeEvent {
    Client(Result<ClientMessage, crate::protocol::ProtocolError>),
    Monitor {
        generation: u64,
        output: MonitorOutput,
    },
}

struct RunningMonitor {
    commands: MonitorCommandSender,
    cancelled: Arc<AtomicBool>,
    forwarder: thread::JoinHandle<()>,
    generation: u64,
}

pub fn run(herdr_bin: &str, epoch: String, output: &mut impl Write) -> io::Result<()> {
    let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
    let cancelled = Arc::new(AtomicBool::new(false));
    spawn_input(events_tx.clone(), Arc::clone(&cancelled));
    let mut runtime = Runtime {
        herdr_bin,
        epoch,
        output,
        events_tx,
        events_rx,
        cancelled,
        discovered: None,
        monitors: BTreeMap::new(),
        snapshots: BTreeMap::new(),
        assigned: BTreeSet::new(),
        next_monitor_generation: 0,
        reported_uncovered: BTreeMap::new(),
    };
    runtime.discover().map_err(|error| match error {
        DiscoveryError::Source(error) | DiscoveryError::Output(error) => error,
    })?;
    runtime.event_loop()
}

struct Runtime<'a, W> {
    herdr_bin: &'a str,
    epoch: String,
    output: &'a mut W,
    events_tx: SyncSender<RuntimeEvent>,
    events_rx: Receiver<RuntimeEvent>,
    cancelled: Arc<AtomicBool>,
    discovered: Option<Vec<SessionDescriptor>>,
    monitors: BTreeMap<String, RunningMonitor>,
    snapshots: BTreeMap<String, Value>,
    assigned: BTreeSet<(String, String)>,
    next_monitor_generation: u64,
    reported_uncovered: BTreeMap<String, Vec<String>>,
}

impl<W: Write> Runtime<'_, W> {
    fn event_loop(&mut self) -> io::Result<()> {
        let mut next_discovery = Instant::now() + DISCOVERY_INTERVAL;
        loop {
            let now = Instant::now();
            if now >= next_discovery {
                match self.discover() {
                    Ok(()) | Err(DiscoveryError::Source(_)) => {}
                    Err(DiscoveryError::Output(error)) => return Err(error),
                }
                next_discovery = Instant::now() + DISCOVERY_INTERVAL;
                continue;
            }
            match self.events_rx.recv_timeout(next_discovery - now) {
                Ok(RuntimeEvent::Client(Ok(ClientMessage::Shutdown { id }))) => {
                    write_server_message(self.output, &ServerMessage::Closed { id })?;
                    self.stop_all();
                    return Ok(());
                }
                Ok(RuntimeEvent::Client(Ok(request @ ClientMessage::Request { .. }))) => {
                    self.forward(request)?;
                }
                Ok(RuntimeEvent::Client(Err(error))) if error.code() == "unexpected_eof" => {
                    self.stop_all();
                    return Ok(());
                }
                Ok(RuntimeEvent::Client(Err(_))) => {
                    self.stop_all();
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "client_protocol_error",
                    ));
                }
                Ok(RuntimeEvent::Monitor { generation, output }) => {
                    self.monitor_output(generation, output)?
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => return Ok(()),
            }
        }
    }

    fn discover(&mut self) -> Result<(), DiscoveryError> {
        let sessions = list_sessions(self.herdr_bin).map_err(DiscoveryError::Source)?;
        if self.discovered.as_ref() != Some(&sessions) {
            write_server_message(
                self.output,
                &ServerMessage::Sessions {
                    sessions: sessions.clone(),
                },
            )
            .map_err(DiscoveryError::Output)?;
            self.discovered = Some(sessions.clone());
        }
        let running = sessions
            .into_iter()
            .filter(|session| session.running && valid_session(&session.name))
            .map(|session| (session.name.clone(), session))
            .collect::<BTreeMap<_, _>>();
        let stale = self
            .monitors
            .keys()
            .filter(|name| !running.contains_key(*name))
            .cloned()
            .collect::<Vec<_>>();
        for name in stale {
            self.stop_monitor(&name);
        }
        for (name, session) in running {
            if !self.monitors.contains_key(&name) {
                self.start_monitor(session)
                    .map_err(DiscoveryError::Output)?;
            }
        }
        self.reallocate().map_err(DiscoveryError::Output)?;
        Ok(())
    }

    fn start_monitor(&mut self, session: SessionDescriptor) -> io::Result<()> {
        let Ok(monitor) = SessionMonitor::start(session.clone()) else {
            write_server_message(
                self.output,
                &ServerMessage::Degraded {
                    session: session.name,
                    epoch: self.epoch.clone(),
                    code: "session_monitor_start_failed".into(),
                    message: "session monitor unavailable".into(),
                    uncovered_pane_ids: Vec::new(),
                },
            )?;
            return Ok(());
        };
        let commands = monitor.command_sender();
        let cancelled = monitor.cancellation();
        let events = self.events_tx.clone();
        let forwarder_cancelled = Arc::clone(&cancelled);
        self.next_monitor_generation += 1;
        let generation = self.next_monitor_generation;
        let forwarder = thread::spawn(move || {
            loop {
                match monitor.recv_timeout(Duration::from_millis(100)) {
                    Ok(output) => {
                        if !send_runtime_event(
                            &events,
                            RuntimeEvent::Monitor { generation, output },
                            &forwarder_cancelled,
                        ) {
                            return;
                        }
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                    Err(mpsc::RecvTimeoutError::Disconnected) => return,
                }
            }
        });
        self.monitors.insert(
            session.name,
            RunningMonitor {
                commands,
                cancelled,
                forwarder,
                generation,
            },
        );
        Ok(())
    }

    fn monitor_output(&mut self, generation: u64, output: MonitorOutput) -> io::Result<()> {
        let session = match &output {
            MonitorOutput::Baseline { session, .. }
            | MonitorOutput::Snapshot { session, .. }
            | MonitorOutput::AgentStatus { session, .. }
            | MonitorOutput::Degraded { session, .. }
            | MonitorOutput::Closed { session } => session,
        };
        if self
            .monitors
            .get(session)
            .is_none_or(|monitor| monitor.generation != generation)
        {
            return Ok(());
        }
        match output {
            MonitorOutput::Baseline { session, snapshot } => {
                self.snapshots.insert(session.clone(), snapshot.clone());
                write_server_message(
                    self.output,
                    &ServerMessage::Snapshot {
                        session,
                        epoch: self.epoch.clone(),
                        baseline: true,
                        snapshot,
                    },
                )?;
                self.reallocate()?;
            }
            MonitorOutput::Snapshot { session, snapshot } => {
                self.snapshots.insert(session.clone(), snapshot.clone());
                write_server_message(
                    self.output,
                    &ServerMessage::Snapshot {
                        session,
                        epoch: self.epoch.clone(),
                        baseline: false,
                        snapshot,
                    },
                )?;
                self.reallocate()?;
            }
            MonitorOutput::AgentStatus {
                session,
                pane_id,
                status,
            } => write_server_message(
                self.output,
                &ServerMessage::AgentStatus {
                    session,
                    epoch: self.epoch.clone(),
                    pane_id,
                    status,
                },
            )?,
            MonitorOutput::Degraded {
                session,
                code,
                message,
                uncovered_pane_ids,
            } => write_server_message(
                self.output,
                &ServerMessage::Degraded {
                    session,
                    epoch: self.epoch.clone(),
                    code,
                    message,
                    uncovered_pane_ids,
                },
            )?,
            MonitorOutput::Closed { session } => {
                self.stop_monitor(&session);
                self.reallocate()?;
            }
        }
        Ok(())
    }

    fn forward(&mut self, message: ClientMessage) -> io::Result<()> {
        let ClientMessage::Request {
            id,
            session,
            method,
            params,
        } = message
        else {
            return Ok(());
        };
        if !valid_session(&session) {
            return self.error(
                Some(id),
                Some(session),
                "invalid_session",
                "session is not running",
            );
        }
        if !valid_method(&method) {
            return self.error(Some(id), Some(session), "invalid_method", "invalid method");
        }
        if serde_json::to_vec(&params).map_or(true, |bytes| bytes.len() > MAX_LINE_BYTES) {
            return self.error(
                Some(id),
                Some(session),
                "invalid_params",
                "params too large",
            );
        }
        let descriptor = self.discovered.as_ref().and_then(|sessions| {
            sessions
                .iter()
                .find(|item| item.name == session && item.running)
        });
        let Some(descriptor) = descriptor else {
            return self.error(
                Some(id),
                Some(session),
                "invalid_session",
                "session is not running",
            );
        };
        let endpoint = match ApiEndpoint::from_reported_path(&descriptor.socket_path) {
            Ok(endpoint) => endpoint,
            Err(_) => {
                return self.error(
                    Some(id),
                    Some(session),
                    "session_unavailable",
                    "session endpoint unavailable",
                );
            }
        };
        let response = api_response_until(
            &endpoint,
            &json!({"id":id,"method":method,"params":params}),
            &self.cancelled,
            Instant::now() + API_CALL_TIMEOUT,
        );
        match response {
            Ok(response) if response.get("error").is_some() => {
                let error = &response["error"];
                self.error(
                    Some(id),
                    Some(session),
                    error["code"].as_str().unwrap_or("herdr_api_error"),
                    error["message"].as_str().unwrap_or("herdr API error"),
                )
            }
            Ok(response) => write_server_message(
                self.output,
                &ServerMessage::Response {
                    id,
                    session,
                    result: response["result"].clone(),
                },
            ),
            Err(error) => self.error(
                Some(id),
                Some(session),
                "session_unavailable",
                error.to_string().as_str(),
            ),
        }
    }

    fn error(
        &mut self,
        id: Option<String>,
        session: Option<String>,
        code: &str,
        message: &str,
    ) -> io::Result<()> {
        write_server_message(
            self.output,
            &ServerMessage::Error {
                id,
                session,
                code: code.into(),
                message: message.into(),
            },
        )
    }

    fn reallocate(&mut self) -> io::Result<()> {
        let previous = self.assigned.clone();
        let ranked = ranked_candidates(&self.snapshots);
        let candidates = ranked.iter().cloned().collect::<BTreeMap<_, _>>();
        self.assigned.retain(|pane| candidates.contains_key(pane));
        while self.assigned.len() < MAX_LIVE_AGENT_SUBSCRIPTIONS {
            let Some(next) = ranked
                .iter()
                .map(|(pane, _)| pane)
                .find(|pane| !self.assigned.contains(*pane))
                .cloned()
            else {
                break;
            };
            self.assigned.insert(next);
        }
        let working = candidates
            .iter()
            .filter(|(_, rank)| rank.working)
            .map(|(pane, _)| pane)
            .collect::<Vec<_>>();
        for pane in working {
            if self.assigned.contains(pane) {
                continue;
            }
            let replace = self
                .assigned
                .iter()
                .find(|covered| !candidates[*covered].working)
                .cloned();
            if let Some(replace) = replace {
                self.assigned.remove(&replace);
                self.assigned.insert(pane.clone());
            }
        }
        if self.assigned != previous {
            for (session, monitor) in &self.monitors {
                let pane_ids = self
                    .assigned
                    .iter()
                    .filter(|(owner, _)| owner == session)
                    .map(|(_, pane)| pane.clone())
                    .collect();
                let _ = monitor
                    .commands
                    .send(MonitorCommand::SetAgentPaneIds(pane_ids));
            }
        }

        let mut current = BTreeMap::<String, Vec<String>>::new();
        for (session, pane_id) in candidates.keys() {
            if !self.assigned.contains(&(session.clone(), pane_id.clone())) {
                current
                    .entry(session.clone())
                    .or_default()
                    .push(pane_id.clone());
            }
        }
        let sessions = self
            .reported_uncovered
            .keys()
            .chain(current.keys())
            .cloned()
            .collect::<BTreeSet<_>>();
        for session in sessions {
            let uncovered = current.remove(&session).unwrap_or_default();
            if self.reported_uncovered.get(&session) == Some(&uncovered)
                || (uncovered.is_empty() && !self.reported_uncovered.contains_key(&session))
            {
                continue;
            }
            let message = if uncovered.is_empty() {
                "agent status live coverage restored".into()
            } else {
                format!(
                    "agent status events unavailable for {} pane{}",
                    uncovered.len(),
                    if uncovered.len() == 1 { "" } else { "s" }
                )
            };
            write_server_message(
                self.output,
                &ServerMessage::Degraded {
                    session: session.clone(),
                    epoch: self.epoch.clone(),
                    code: "agent_live_coverage_degraded".into(),
                    message,
                    uncovered_pane_ids: uncovered.clone(),
                },
            )?;
            if uncovered.is_empty() {
                self.reported_uncovered.remove(&session);
            } else {
                self.reported_uncovered.insert(session, uncovered);
            }
        }
        Ok(())
    }

    fn stop_monitor(&mut self, session: &str) {
        let Some(monitor) = self.monitors.remove(session) else {
            return;
        };
        monitor.cancelled.store(true, Ordering::Release);
        let _ = monitor.forwarder.join();
        self.assigned.retain(|(owner, _)| owner != session);
        self.snapshots.remove(session);
    }

    fn stop_all(&mut self) {
        self.cancelled.store(true, Ordering::Release);
        let names = self.monitors.keys().cloned().collect::<Vec<_>>();
        for name in names {
            self.stop_monitor(&name);
        }
    }
}

#[derive(Deserialize)]
struct SessionList {
    sessions: Vec<SessionDescriptor>,
}

enum DiscoveryError {
    Source(io::Error),
    Output(io::Error),
}

fn list_sessions(herdr_bin: &str) -> io::Result<Vec<SessionDescriptor>> {
    let output = child_stdout(herdr_bin, ["session", "list", "--json"])?;
    serde_json::from_slice::<SessionList>(&output)
        .map(|list| list.sessions)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "session_list_invalid"))
}

pub(crate) fn child_stdout<const N: usize>(
    program: &str,
    arguments: [&str; N],
) -> io::Result<Vec<u8>> {
    let mut child = Command::new(program)
        .args(arguments)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut stdout = child.stdout.take().expect("stdout was piped");
    let reader = thread::spawn(move || {
        let mut output = Vec::new();
        let mut buffer = [0_u8; 8192];
        let mut exceeded = false;
        loop {
            let count = stdout.read(&mut buffer)?;
            if count == 0 {
                return if exceeded {
                    Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "herdr_child_output_too_large",
                    ))
                } else {
                    Ok(output)
                };
            }
            if output.len() + count <= MAX_CHILD_STDOUT_BYTES {
                output.extend_from_slice(&buffer[..count]);
            } else {
                exceeded = true;
            }
        }
    });
    let deadline = Instant::now() + CHILD_TIMEOUT;
    loop {
        if let Some(status) = child.try_wait()? {
            let output = reader
                .join()
                .map_err(|_| io::Error::other("herdr_child_reader_panicked"))??;
            if !status.success() {
                return Err(io::Error::other("herdr_child_failed"));
            }
            return Ok(output);
        }
        if Instant::now() >= deadline {
            child.kill()?;
            child.wait()?;
            let _ = reader.join();
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "herdr_child_timeout",
            ));
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn spawn_input(events: SyncSender<RuntimeEvent>, cancelled: Arc<AtomicBool>) {
    thread::spawn(move || {
        let stdin = io::stdin();
        let mut input = BufReader::new(stdin.lock());
        loop {
            let message = read_client_message(&mut input);
            if matches!(message, Ok(ClientMessage::Shutdown { .. })) {
                cancelled.store(true, Ordering::Release);
            }
            if events.send(RuntimeEvent::Client(message)).is_err() {
                return;
            }
            if cancelled.load(Ordering::Acquire) {
                return;
            }
        }
    });
}

fn send_runtime_event(
    sender: &SyncSender<RuntimeEvent>,
    mut event: RuntimeEvent,
    cancelled: &AtomicBool,
) -> bool {
    loop {
        match sender.try_send(event) {
            Ok(()) => return true,
            Err(TrySendError::Full(returned)) => {
                event = returned;
                if cancelled.load(Ordering::Acquire) {
                    return false;
                }
                thread::sleep(Duration::from_millis(5));
            }
            Err(TrySendError::Disconnected(_)) => return false,
        }
    }
}

#[derive(Clone, Copy)]
struct Rank {
    working: bool,
    focused: bool,
}

fn ranked_candidates(snapshots: &BTreeMap<String, Value>) -> Vec<((String, String), Rank)> {
    let mut ranked = snapshots
        .iter()
        .flat_map(|(session, snapshot)| {
            let focused = snapshot["focused_pane_id"].as_str();
            snapshot["panes"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(move |pane| {
                    let pane_id = pane["pane_id"].as_str()?;
                    Some((
                        (session.clone(), pane_id.into()),
                        Rank {
                            working: pane["agent_status"] == "working",
                            focused: focused == Some(pane_id),
                        },
                    ))
                })
        })
        .collect::<Vec<_>>();
    ranked.sort_by(|(left, left_rank), (right, right_rank)| {
        right_rank
            .working
            .cmp(&left_rank.working)
            .then_with(|| right_rank.focused.cmp(&left_rank.focused))
            .then_with(|| left.cmp(right))
    });
    ranked
}

fn valid_session(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
}
fn valid_method(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 96
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'.' || byte == b'_'
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        path::PathBuf,
        process,
        sync::atomic::AtomicBool,
        time::{SystemTime, UNIX_EPOCH},
    };

    struct TestFile(PathBuf);

    impl Drop for TestFile {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.0);
        }
    }

    fn empty_session_list_program() -> TestFile {
        let path = std::env::temp_dir().join(format!(
            "herdroid-periodic-discovery-test-{}-{}{}",
            process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            if cfg!(windows) { ".cmd" } else { ".sh" }
        ));
        #[cfg(windows)]
        fs::write(&path, "@echo off\r\necho {\"sessions\":[]}\r\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::write(&path, "#!/bin/sh\nprintf '%s\\n' '{\"sessions\":[]}'\n").unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        }
        TestFile(path)
    }

    fn child_stdout_of_size(size: usize) -> io::Result<Vec<u8>> {
        let file = TestFile(std::env::temp_dir().join(format!(
            "herdroid-child-stdout-test-{}-{}",
            process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )));
        fs::write(&file.0, vec![b'x'; size]).unwrap();
        #[cfg(windows)]
        return child_stdout("cmd.exe", ["/D", "/C", "type", file.0.to_str().unwrap()]);
        #[cfg(unix)]
        child_stdout("cat", [file.0.to_str().unwrap()])
    }

    #[test]
    fn child_stdout_drains_pipe_sized_output_before_waiting_for_exit() {
        let output = child_stdout_of_size(262_144).unwrap();
        assert_eq!(output.len(), 262_144);
    }

    #[test]
    fn child_stdout_reaps_process_after_draining_bounded_output() {
        let error = child_stdout_of_size(MAX_CHILD_STDOUT_BYTES + 1).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(error.to_string(), "herdr_child_output_too_large");
    }

    #[test]
    fn cancelled_runtime_event_send_stops_when_queue_is_full() {
        let (sender, _receiver) = mpsc::sync_channel(1);
        sender
            .send(RuntimeEvent::Monitor {
                generation: 1,
                output: MonitorOutput::Closed {
                    session: "first".into(),
                },
            })
            .unwrap();
        let cancelled = AtomicBool::new(true);

        let sent = send_runtime_event(
            &sender,
            RuntimeEvent::Monitor {
                generation: 2,
                output: MonitorOutput::Closed {
                    session: "second".into(),
                },
            },
            &cancelled,
        );

        assert!(!sent);
    }

    struct OtherWriter;

    impl Write for OtherWriter {
        fn write(&mut self, _buffer: &[u8]) -> io::Result<usize> {
            Err(io::Error::other("periodic_output_failed"))
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    fn snapshot(panes: &[(&str, &str)]) -> Value {
        json!({"panes": panes.iter().map(|(pane_id, agent_status)| json!({"pane_id":pane_id,"agent_status":agent_status})).collect::<Vec<_>>()})
    }

    #[test]
    fn periodic_discovery_propagates_non_broken_pipe_output_errors() {
        let program = empty_session_list_program();
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let delayed_eof = events_tx.clone();
        thread::spawn(move || {
            thread::sleep(DISCOVERY_INTERVAL + Duration::from_secs(1));
            let mut empty = BufReader::new(io::empty());
            let _ = delayed_eof.send(RuntimeEvent::Client(read_client_message(&mut empty)));
        });
        let mut output = OtherWriter;
        let mut runtime = Runtime {
            herdr_bin: program.0.to_str().unwrap(),
            epoch: "e".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(vec![SessionDescriptor {
                name: "old".into(),
                running: false,
                socket_path: "old.sock".into(),
            }]),
            monitors: BTreeMap::new(),
            snapshots: BTreeMap::new(),
            assigned: BTreeSet::new(),
            next_monitor_generation: 0,
            reported_uncovered: BTreeMap::new(),
        };

        let error = runtime.event_loop().unwrap_err();

        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(error.to_string(), "periodic_output_failed");
    }

    #[test]
    fn nine_panes_keep_a_global_eight_pane_budget() {
        let snapshots = BTreeMap::from([(
            "work".into(),
            snapshot(&[
                ("p1", "idle"),
                ("p2", "idle"),
                ("p3", "idle"),
                ("p4", "idle"),
                ("p5", "idle"),
                ("p6", "idle"),
                ("p7", "idle"),
                ("p8", "idle"),
                ("p9", "idle"),
            ]),
        )]);
        assert_eq!(
            ranked_candidates(&snapshots)
                .into_iter()
                .take(MAX_LIVE_AGENT_SUBSCRIPTIONS)
                .count(),
            8
        );
    }

    #[test]
    fn pane_reordering_keeps_the_same_ranked_set() {
        let first = BTreeMap::from([(
            "work".into(),
            snapshot(&[("p1", "idle"), ("p2", "working")]),
        )]);
        let second = BTreeMap::from([(
            "work".into(),
            snapshot(&[("p2", "working"), ("p1", "idle")]),
        )]);
        let ids = |snapshots: &BTreeMap<String, Value>| {
            ranked_candidates(snapshots)
                .into_iter()
                .map(|(pane, _)| pane)
                .collect::<BTreeSet<_>>()
        };
        assert_eq!(ids(&first), ids(&second));
    }

    #[test]
    fn reallocate_keeps_eight_global_panes_and_replaces_idle_with_working() {
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let (first_tx, first_rx) = crate::monitor::monitor_command_channel();
        let (second_tx, second_rx) = crate::monitor::monitor_command_channel();
        let mut output = Vec::new();
        let monitor = |commands| RunningMonitor {
            commands,
            cancelled: Arc::new(AtomicBool::new(false)),
            forwarder: thread::spawn(|| {}),
            generation: 0,
        };
        let mut runtime = Runtime {
            herdr_bin: "",
            epoch: "e".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(Vec::new()),
            monitors: BTreeMap::from([
                ("a".into(), monitor(first_tx)),
                ("b".into(), monitor(second_tx)),
            ]),
            snapshots: BTreeMap::from([
                (
                    "a".into(),
                    snapshot(&[
                        ("a1", "idle"),
                        ("a2", "idle"),
                        ("a3", "idle"),
                        ("a4", "idle"),
                        ("a5", "idle"),
                        ("a6", "idle"),
                        ("a7", "idle"),
                        ("a8", "idle"),
                    ]),
                ),
                ("b".into(), snapshot(&[("b1", "idle")])),
            ]),
            assigned: BTreeSet::new(),
            next_monitor_generation: 0,
            reported_uncovered: BTreeMap::new(),
        };
        runtime.reallocate().unwrap();
        let _ = first_rx.recv().unwrap();
        let _ = second_rx.recv().unwrap();
        assert_eq!(runtime.assigned.len(), 8);
        runtime
            .snapshots
            .insert("b".into(), snapshot(&[("b1", "working")]));
        runtime.reallocate().unwrap();
        let _ = first_rx.recv().unwrap();
        let _ = second_rx.recv().unwrap();
        assert!(
            runtime
                .assigned
                .contains(&(String::from("b"), String::from("b1")))
        );
        assert_eq!(runtime.assigned.len(), 8);
    }

    #[test]
    fn equivalent_snapshot_refresh_sends_no_new_allocation_command() {
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let (commands, received) = crate::monitor::monitor_command_channel();
        let mut output = Vec::new();
        let mut runtime = Runtime {
            herdr_bin: "",
            epoch: "e".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(Vec::new()),
            monitors: BTreeMap::from([(
                "work".into(),
                RunningMonitor {
                    commands,
                    cancelled: Arc::new(AtomicBool::new(false)),
                    forwarder: thread::spawn(|| {}),
                    generation: 0,
                },
            )]),
            snapshots: BTreeMap::from([(
                "work".into(),
                snapshot(&[("p1", "idle"), ("p2", "working")]),
            )]),
            assigned: BTreeSet::new(),
            next_monitor_generation: 0,
            reported_uncovered: BTreeMap::new(),
        };
        runtime.reallocate().unwrap();
        assert_eq!(
            received.recv().unwrap(),
            MonitorCommand::SetAgentPaneIds(vec!["p1".into(), "p2".into()])
        );

        runtime.snapshots.insert(
            "work".into(),
            snapshot(&[("p2", "working"), ("p1", "done")]),
        );
        runtime.reallocate().unwrap();

        assert_eq!(
            received.recv_timeout(Duration::from_millis(50)),
            Err(mpsc::RecvTimeoutError::Timeout)
        );
    }

    #[test]
    fn allocation_reports_exact_uncovered_panes_then_clears_degraded_state() {
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let (commands, _received) = crate::monitor::monitor_command_channel();
        let mut output = Vec::new();
        let monitor = RunningMonitor {
            commands,
            cancelled: Arc::new(AtomicBool::new(false)),
            forwarder: thread::spawn(|| {}),
            generation: 0,
        };
        let mut runtime = Runtime {
            herdr_bin: "",
            epoch: "epoch-1".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(Vec::new()),
            monitors: BTreeMap::from([("work".into(), monitor)]),
            snapshots: BTreeMap::from([(
                "work".into(),
                snapshot(&[
                    ("p1", "idle"),
                    ("p2", "idle"),
                    ("p3", "idle"),
                    ("p4", "idle"),
                    ("p5", "idle"),
                    ("p6", "idle"),
                    ("p7", "idle"),
                    ("p8", "idle"),
                    ("p9", "idle"),
                ]),
            )]),
            assigned: BTreeSet::new(),
            next_monitor_generation: 0,
            reported_uncovered: BTreeMap::new(),
        };
        runtime.reallocate().unwrap();
        runtime
            .snapshots
            .insert("work".into(), snapshot(&[("p1", "idle")]));
        runtime.reallocate().unwrap();

        let messages = String::from_utf8(output)
            .unwrap()
            .lines()
            .map(|line| serde_json::from_str::<ServerMessage>(line).unwrap())
            .collect::<Vec<_>>();
        assert_eq!(
            messages,
            vec![
                ServerMessage::Degraded {
                    session: "work".into(),
                    epoch: "epoch-1".into(),
                    code: "agent_live_coverage_degraded".into(),
                    message: "agent status events unavailable for 1 pane".into(),
                    uncovered_pane_ids: vec!["p9".into()],
                },
                ServerMessage::Degraded {
                    session: "work".into(),
                    epoch: "epoch-1".into(),
                    code: "agent_live_coverage_degraded".into(),
                    message: "agent status live coverage restored".into(),
                    uncovered_pane_ids: Vec::new(),
                },
            ]
        );
    }

    #[test]
    fn request_validation_returns_stable_bridge_errors() {
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let mut output = Vec::new();
        let mut runtime = Runtime {
            herdr_bin: "",
            epoch: "e".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(vec![SessionDescriptor {
                name: "work".into(),
                running: true,
                socket_path: String::new(),
            }]),
            monitors: BTreeMap::new(),
            snapshots: BTreeMap::new(),
            assigned: BTreeSet::new(),
            next_monitor_generation: 0,
            reported_uncovered: BTreeMap::new(),
        };
        for (id, session, method, params) in [
            ("session", "bad session", "session.snapshot", json!({})),
            ("method", "work", "Session.Snapshot", json!({})),
            (
                "params",
                "work",
                "session.snapshot",
                json!("x".repeat(MAX_LINE_BYTES + 1)),
            ),
        ] {
            runtime
                .forward(ClientMessage::Request {
                    id: id.into(),
                    session: session.into(),
                    method: method.into(),
                    params,
                })
                .unwrap();
        }
        drop(runtime);

        let messages = String::from_utf8(output)
            .unwrap()
            .lines()
            .map(|line| serde_json::from_str::<ServerMessage>(line).unwrap())
            .collect::<Vec<_>>();
        assert_eq!(
            messages,
            vec![
                ServerMessage::Error {
                    id: Some("session".into()),
                    session: Some("bad session".into()),
                    code: "invalid_session".into(),
                    message: "session is not running".into(),
                },
                ServerMessage::Error {
                    id: Some("method".into()),
                    session: Some("work".into()),
                    code: "invalid_method".into(),
                    message: "invalid method".into(),
                },
                ServerMessage::Error {
                    id: Some("params".into()),
                    session: Some("work".into()),
                    code: "invalid_params".into(),
                    message: "params too large".into(),
                },
            ]
        );
    }

    #[test]
    fn stale_monitor_output_cannot_restore_removed_or_replaced_snapshot() {
        let (events_tx, events_rx) = mpsc::sync_channel(RUNTIME_EVENT_CAPACITY);
        let (commands, _received) = crate::monitor::monitor_command_channel();
        let mut output = Vec::new();
        let mut runtime = Runtime {
            herdr_bin: "",
            epoch: "e".into(),
            output: &mut output,
            events_tx,
            events_rx,
            cancelled: Arc::new(AtomicBool::new(false)),
            discovered: Some(Vec::new()),
            monitors: BTreeMap::from([(
                "work".into(),
                RunningMonitor {
                    commands,
                    cancelled: Arc::new(AtomicBool::new(false)),
                    forwarder: thread::spawn(|| {}),
                    generation: 2,
                },
            )]),
            snapshots: BTreeMap::from([("work".into(), snapshot(&[("new", "idle")]))]),
            assigned: BTreeSet::new(),
            next_monitor_generation: 2,
            reported_uncovered: BTreeMap::new(),
        };
        runtime
            .monitor_output(
                1,
                MonitorOutput::Baseline {
                    session: "work".into(),
                    snapshot: snapshot(&[("old", "working")]),
                },
            )
            .unwrap();
        assert_eq!(runtime.snapshots["work"]["panes"][0]["pane_id"], "new");
    }
}
