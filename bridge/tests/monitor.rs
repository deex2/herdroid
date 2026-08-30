#[path = "../src/herdr.rs"]
mod herdr;
#[path = "../src/ipc.rs"]
mod ipc;
#[path = "../src/monitor.rs"]
mod monitor;
#[allow(dead_code)]
#[path = "../src/protocol.rs"]
mod protocol;

use std::{
    collections::VecDeque,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use interprocess::local_socket::{ListenerOptions, traits::Listener as _};
use monitor::{MonitorCommand, MonitorOutput, SessionMonitor};
use protocol::SessionDescriptor;
use serde_json::{Value, json};

static NEXT_FAKE_ID: AtomicU64 = AtomicU64::new(0);

struct FakeHerdrSession {
    descriptor: SessionDescriptor,
    requests: Arc<Mutex<Vec<Value>>>,
    stopped: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    socket_path: PathBuf,
}

#[derive(Clone)]
enum AgentReply {
    Started(Vec<(Duration, Value)>),
    DelayedStart(Duration, Vec<(Duration, Value)>),
    Disconnected,
    StartedThenDisconnected(Vec<(Duration, Value)>),
    Rejected,
    DelayedRejected(Duration),
    StartedProbingDisconnect(Duration, Arc<AtomicU64>),
}

#[derive(Clone, Copy)]
enum StructuralReply {
    Started,
    Disconnect,
    DelayedDisconnect(Duration),
    Rejected,
}

impl FakeHerdrSession {
    fn with_panes(pane_ids: impl IntoIterator<Item = &'static str>) -> Self {
        Self::scripted(
            [snapshot(
                pane_ids.into_iter().map(|pane_id| (pane_id, "idle")),
            )],
            Vec::new(),
            Vec::new(),
        )
    }

    fn scripted(
        snapshots: impl IntoIterator<Item = Value>,
        structural_events: Vec<(Duration, Value)>,
        agent_replies: Vec<AgentReply>,
    ) -> Self {
        Self::scripted_with_structural_reply(
            snapshots,
            structural_events,
            agent_replies,
            StructuralReply::Started,
        )
    }

    fn scripted_with_structural_reply(
        snapshots: impl IntoIterator<Item = Value>,
        structural_events: Vec<(Duration, Value)>,
        agent_replies: Vec<AgentReply>,
        structural_reply: StructuralReply,
    ) -> Self {
        let socket_path = std::env::temp_dir().join(format!(
            "herdroid-monitor-test-{}-{}.sock",
            std::process::id(),
            NEXT_FAKE_ID.fetch_add(1, Ordering::Relaxed)
        ));
        let listener = bind(&socket_path);
        let requests = Arc::new(Mutex::new(Vec::new()));
        let worker_requests = Arc::clone(&requests);
        let stopped = Arc::new(AtomicBool::new(false));
        let worker_stopped = Arc::clone(&stopped);
        let snapshots = Arc::new(Mutex::new(snapshots.into_iter().collect()));
        let agent_replies = Arc::new(Mutex::new(agent_replies.into()));
        let worker = thread::spawn(move || {
            let mut handlers = Vec::new();
            while !worker_stopped.load(Ordering::Acquire) {
                let Ok(stream) = listener.accept() else {
                    break;
                };
                let requests = Arc::clone(&worker_requests);
                let stopped = Arc::clone(&worker_stopped);
                let snapshots = Arc::clone(&snapshots);
                let agent_replies = Arc::clone(&agent_replies);
                let structural_events = structural_events.clone();
                handlers.push(thread::spawn(move || {
                    handle_connection(
                        stream,
                        requests,
                        snapshots,
                        structural_events,
                        agent_replies,
                        structural_reply,
                        stopped,
                    )
                }));
            }
            for handler in handlers {
                handler.join().unwrap();
            }
        });
        Self {
            descriptor: SessionDescriptor {
                name: "work".into(),
                running: true,
                socket_path: socket_path.to_string_lossy().into_owned(),
            },
            requests,
            stopped,
            worker: Some(worker),
            socket_path,
        }
    }

    fn request_order(&self) -> Vec<String> {
        self.requests
            .lock()
            .unwrap()
            .iter()
            .map(|request| match request["method"].as_str().unwrap() {
                "events.subscribe"
                    if request["params"]["subscriptions"][0]["type"]
                        == "pane.agent_status_changed" =>
                {
                    "events.subscribe:agent-unfiltered".into()
                }
                "events.subscribe" => "events.subscribe:structural".into(),
                method => method.into(),
            })
            .collect()
    }

    fn agent_request_json(&self) -> Value {
        self.requests
            .lock()
            .unwrap()
            .iter()
            .find(|request| {
                request["params"]["subscriptions"][0]["type"] == "pane.agent_status_changed"
            })
            .unwrap()
            .clone()
    }

    fn requests(&self) -> Vec<Value> {
        self.requests.lock().unwrap().clone()
    }
}

impl Drop for FakeHerdrSession {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Release);
        if let Ok(endpoint) = ipc::ApiEndpoint::from_reported_path(&self.socket_path) {
            let _ = herdr::api_call(
                &endpoint,
                &json!({"id":"stop","method":"stop-test","params":{}}),
            );
        }
        if let Some(worker) = self.worker.take() {
            worker.join().unwrap();
        }
        #[cfg(unix)]
        let _ = std::fs::remove_file(&self.socket_path);
    }
}

fn handle_connection(
    mut stream: interprocess::local_socket::Stream,
    requests: Arc<Mutex<Vec<Value>>>,
    snapshots: Arc<Mutex<VecDeque<Value>>>,
    structural_events: Vec<(Duration, Value)>,
    agent_replies: Arc<Mutex<VecDeque<AgentReply>>>,
    structural_reply: StructuralReply,
    stopped: Arc<AtomicBool>,
) {
    let mut line = Vec::new();
    let mut reader = BufReader::new(&mut stream);
    loop {
        match reader.read_until(b'\n', &mut line) {
            Ok(_) if line.ends_with(b"\n") => break,
            Ok(_) if stopped.load(Ordering::Acquire) => return,
            Ok(_) => thread::sleep(Duration::from_millis(5)),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("read fake request: {error}"),
        }
    }
    drop(reader);
    let request: Value = serde_json::from_slice(&line).unwrap();
    requests.lock().unwrap().push(request.clone());
    match request["method"].as_str().unwrap() {
        "session.snapshot" => {
            let mut snapshots = snapshots.lock().unwrap();
            let snapshot = if snapshots.len() > 1 {
                snapshots.pop_front().unwrap()
            } else {
                snapshots.front().unwrap().clone()
            };
            write_line(&mut stream, &json!({"id":request["id"], "result":snapshot}));
        }
        "events.subscribe" => {
            let is_agent =
                request["params"]["subscriptions"]
                    .as_array()
                    .is_some_and(|subscriptions| {
                        subscriptions.first().is_some_and(|subscription| {
                            subscription["type"] == "pane.agent_status_changed"
                        })
                    });
            if !is_agent {
                if matches!(structural_reply, StructuralReply::Rejected) {
                    write_line(
                        &mut stream,
                        &json!({"id":request["id"], "error":{"code":"timeout", "message":"subscription unavailable"}}),
                    );
                    return;
                }
                write_line(
                    &mut stream,
                    &json!({"id":request["id"], "result":{"type":"subscription_started"}}),
                );
                for (delay, event) in structural_events {
                    thread::sleep(delay);
                    write_line(&mut stream, &event);
                }
                match structural_reply {
                    StructuralReply::Disconnect => return,
                    StructuralReply::DelayedDisconnect(delay) => {
                        thread::sleep(delay);
                        return;
                    }
                    StructuralReply::Started | StructuralReply::Rejected => {}
                }
            } else {
                let (start_delay, events, disconnect) = match agent_replies
                    .lock()
                    .unwrap()
                    .pop_front()
                    .unwrap_or(AgentReply::Started(Vec::new()))
                {
                    AgentReply::Rejected => {
                        write_line(
                            &mut stream,
                            &json!({"id":request["id"], "error":{"code":"not_found", "message":"pane not found"}}),
                        );
                        return;
                    }
                    AgentReply::DelayedRejected(delay) => {
                        thread::sleep(delay);
                        write_line(
                            &mut stream,
                            &json!({"id":request["id"], "error":{"code":"not_found", "message":"pane not found"}}),
                        );
                        return;
                    }
                    AgentReply::Started(events) => (Duration::ZERO, events, false),
                    AgentReply::DelayedStart(delay, events) => (delay, events, false),
                    AgentReply::Disconnected => (Duration::ZERO, Vec::new(), true),
                    AgentReply::StartedThenDisconnected(events) => (Duration::ZERO, events, true),
                    AgentReply::StartedProbingDisconnect(delay, probe_result) => {
                        write_line(
                            &mut stream,
                            &json!({"id":request["id"], "result":{"type":"subscription_started"}}),
                        );
                        thread::sleep(delay);
                        probe_result.store(
                            if try_write_line(&mut stream, &agent_event("ws:p1", "working")) {
                                1
                            } else {
                                2
                            },
                            Ordering::Release,
                        );
                        return;
                    }
                };
                thread::sleep(start_delay);
                write_line(
                    &mut stream,
                    &json!({"id":request["id"], "result":{"type":"subscription_started"}}),
                );
                for (delay, event) in events {
                    thread::sleep(delay);
                    if !try_write_line(&mut stream, &event) {
                        return;
                    }
                }
                if disconnect {
                    return;
                }
            }
            while !stopped.load(Ordering::Acquire) {
                thread::sleep(Duration::from_millis(10));
            }
        }
        "stop-test" => {}
        method => panic!("unexpected fake Herdr method: {method}"),
    }
}

fn write_line(stream: &mut interprocess::local_socket::Stream, value: &Value) {
    assert!(try_write_line(stream, value));
}

fn try_write_line(stream: &mut interprocess::local_socket::Stream, value: &Value) -> bool {
    serde_json::to_writer(&mut *stream, value).is_ok()
        && stream.write_all(b"\n").is_ok()
        && stream.flush().is_ok()
}

#[cfg(unix)]
fn bind(path: &Path) -> interprocess::local_socket::Listener {
    use interprocess::local_socket::{GenericFilePath, prelude::*};

    ListenerOptions::new()
        .name(path.to_fs_name::<GenericFilePath>().unwrap())
        .create_sync()
        .unwrap()
}

#[cfg(windows)]
fn bind(path: &Path) -> interprocess::local_socket::Listener {
    use interprocess::local_socket::{GenericNamespaced, prelude::*};

    ListenerOptions::new()
        .name(
            path.to_string_lossy()
                .to_ns_name::<GenericNamespaced>()
                .unwrap(),
        )
        .create_sync()
        .unwrap()
}

fn run_monitor(fake: &FakeHerdrSession) -> Vec<MonitorOutput> {
    let monitor = SessionMonitor::start(fake.descriptor.clone()).unwrap();
    let baseline = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
    let pane_ids = match &baseline {
        MonitorOutput::Baseline { snapshot, .. } => snapshot["panes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|pane| pane["pane_id"].as_str().unwrap().to_owned())
            .collect(),
        output => panic!("expected baseline, got {output:?}"),
    };
    monitor
        .send(MonitorCommand::SetAgentPaneIds(pane_ids))
        .unwrap();
    let mut outputs = vec![baseline];
    let deadline = Instant::now() + Duration::from_millis(750);
    while Instant::now() < deadline {
        if let Ok(output) = monitor.recv_timeout(Duration::from_millis(50)) {
            outputs.push(output);
        }
    }
    outputs
}

fn snapshot(panes: impl IntoIterator<Item = (&'static str, &'static str)>) -> Value {
    let mut snapshot: Value =
        serde_json::from_str(include_str!("fixtures/session-snapshot.json")).unwrap();
    let template = snapshot["panes"][0].clone();
    snapshot["panes"] = Value::Array(
        panes
            .into_iter()
            .map(|(pane_id, status)| {
                let mut pane = template.clone();
                pane["pane_id"] = pane_id.into();
                pane["agent_status"] = status.into();
                pane
            })
            .collect(),
    );
    snapshot
}

fn agent_event(pane_id: &str, status: &str) -> Value {
    json!({
        "event":"pane.agent_status_changed",
        "data":{
            "pane_id":pane_id,
            "workspace_id":"ws",
            "agent_status":status,
            "state_labels":{}
        }
    })
}

fn start_and_assign(fake: &FakeHerdrSession, pane_ids: &[&str]) -> (SessionMonitor, MonitorOutput) {
    let monitor = SessionMonitor::start(fake.descriptor.clone()).unwrap();
    let baseline = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
    monitor
        .send(MonitorCommand::SetAgentPaneIds(
            pane_ids.iter().map(|pane_id| (*pane_id).into()).collect(),
        ))
        .unwrap();
    (monitor, baseline)
}

fn collect_for(monitor: &SessionMonitor, duration: Duration) -> Vec<MonitorOutput> {
    let deadline = Instant::now() + duration;
    let mut outputs = Vec::new();
    while Instant::now() < deadline {
        if let Ok(output) = monitor.recv_timeout(Duration::from_millis(50)) {
            outputs.push(output);
        }
    }
    outputs
}

#[test]
fn snapshot_precedes_unfiltered_agent_subscription() {
    let fake = FakeHerdrSession::with_panes(["ws:p1"]);
    let outputs = run_monitor(&fake);

    assert_eq!(
        &fake.request_order()[..3],
        [
            "events.subscribe:structural",
            "session.snapshot",
            "events.subscribe:agent-unfiltered"
        ]
    );
    for subscription in fake.agent_request_json()["params"]["subscriptions"]
        .as_array()
        .unwrap()
    {
        assert!(subscription.get("agent_status").is_none());
    }
    assert!(matches!(outputs[0], MonitorOutput::Baseline { .. }));
}

#[test]
fn structural_request_covers_snapshot_changes_without_high_volume_events() {
    let fake = FakeHerdrSession::with_panes(["ws:p1"]);
    let _outputs = run_monitor(&fake);
    let requests = fake.requests();
    let subscriptions = requests[0]["params"]["subscriptions"]
        .as_array()
        .unwrap()
        .iter()
        .map(|subscription| subscription["type"].as_str().unwrap())
        .collect::<Vec<_>>();

    assert_eq!(
        subscriptions,
        [
            "workspace.created",
            "workspace.updated",
            "workspace.metadata_updated",
            "workspace.renamed",
            "workspace.moved",
            "workspace.reordered",
            "workspace.closed",
            "workspace.focused",
            "worktree.created",
            "worktree.opened",
            "worktree.removed",
            "tab.created",
            "tab.closed",
            "tab.focused",
            "tab.renamed",
            "tab.moved",
            "pane.created",
            "pane.closed",
            "pane.updated",
            "pane.focused",
            "pane.moved",
            "pane.exited",
            "pane.agent_detected",
            "layout.updated"
        ]
    );
    assert!(!subscriptions.contains(&"pane.output_changed"));
    assert!(!subscriptions.contains(&"pane.agent_status_changed"));
    assert!(!subscriptions.contains(&"pane.scroll_changed"));
}

#[test]
fn event_after_baseline_retains_its_status_transition() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")])],
        Vec::new(),
        vec![AgentReply::Started(vec![(
            Duration::ZERO,
            agent_event("ws:p1", "working"),
        )])],
    );
    let (monitor, baseline) = start_and_assign(&fake, &["ws:p1"]);
    let outputs = collect_for(&monitor, Duration::from_millis(500));

    assert!(matches!(baseline, MonitorOutput::Baseline { .. }));
    assert!(outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p1".into(),
        status: "working".into(),
    }));
}

#[test]
fn done_to_idle_is_emitted_only_as_status_state() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "working")])],
        Vec::new(),
        vec![AgentReply::Started(vec![
            (Duration::ZERO, agent_event("ws:p1", "done")),
            (Duration::ZERO, agent_event("ws:p1", "idle")),
        ])],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let statuses = collect_for(&monitor, Duration::from_millis(500))
        .into_iter()
        .filter_map(|output| match output {
            MonitorOutput::AgentStatus { status, .. } => Some(status),
            _ => None,
        })
        .collect::<Vec<_>>();

    assert_eq!(statuses, ["done", "idle"]);
}

#[test]
fn new_pane_snapshot_seeds_status_without_agent_alert() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "done")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![
            AgentReply::Started(Vec::new()),
            AgentReply::Started(vec![(Duration::ZERO, agent_event("ws:p2", "done"))]),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let snapshot = loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break output;
        }
    };
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_millis(1400));

    assert!(matches!(snapshot, MonitorOutput::Snapshot { .. }));
    assert_eq!(
        fake.requests()
            .iter()
            .filter(|request| {
                request["method"] == "events.subscribe"
                    && request["params"]["subscriptions"][0]["type"] == "pane.agent_status_changed"
            })
            .count(),
        2
    );
    assert!(!outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p2".into(),
        status: "done".into(),
    }));
}

#[test]
fn reconciliation_seeds_status_for_previously_uncovered_pane() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")]), snapshot([("ws:p1", "done")])],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_updated", "data":{"pane":{"pane_id":"ws:p1"}}}),
        )],
        vec![AgentReply::Started(vec![(
            Duration::ZERO,
            agent_event("ws:p1", "done"),
        )])],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &[]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec!["ws:p1".into()]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_millis(500));

    assert!(!outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p1".into(),
        status: "done".into(),
    }));
}

#[test]
fn old_b_transition_during_b_prime_handshake_is_retained() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![
            AgentReply::Started(vec![(
                Duration::from_millis(1400),
                agent_event("ws:p1", "done"),
            )]),
            AgentReply::DelayedStart(Duration::from_millis(300), Vec::new()),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_secs(2));

    assert!(outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p1".into(),
        status: "done".into(),
    }));
}

#[test]
fn skewed_b_prime_overlap_deduplicates_each_ordered_transition_pair() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![
            AgentReply::Started(vec![
                (Duration::from_millis(1400), agent_event("ws:p1", "working")),
                (Duration::ZERO, agent_event("ws:p1", "done")),
            ]),
            AgentReply::DelayedStart(
                Duration::from_millis(300),
                vec![
                    (Duration::ZERO, agent_event("ws:p1", "working")),
                    (Duration::ZERO, agent_event("ws:p1", "done")),
                ],
            ),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let statuses = collect_for(&monitor, Duration::from_secs(2))
        .into_iter()
        .filter_map(|output| match output {
            MonitorOutput::AgentStatus {
                pane_id, status, ..
            } if pane_id == "ws:p1" => Some(status),
            _ => None,
        })
        .collect::<Vec<_>>();

    assert_eq!(statuses, ["working", "done"]);
}

#[test]
fn stale_pane_rejection_refetches_snapshot_and_retries_once() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
            snapshot([("ws:p1", "idle")]),
        ],
        Vec::new(),
        vec![AgentReply::Rejected, AgentReply::Started(Vec::new())],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1", "ws:p2"]);
    let outputs = collect_for(&monitor, Duration::from_millis(750));
    let order = fake.request_order();

    assert_eq!(
        order,
        [
            "events.subscribe:structural",
            "session.snapshot",
            "events.subscribe:agent-unfiltered",
            "session.snapshot",
            "events.subscribe:agent-unfiltered"
        ]
    );
    assert!(
        outputs
            .iter()
            .any(|output| matches!(output, MonitorOutput::Snapshot { .. }))
    );
    assert!(!outputs.iter().any(|output| matches!(
        output,
        MonitorOutput::Degraded { code, .. } if code == "agent_subscription_degraded"
    )));
}

#[test]
fn second_b_prime_failure_keeps_old_b_and_reports_uncovered_panes() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![
            AgentReply::Started(vec![(
                Duration::from_millis(1800),
                agent_event("ws:p1", "done"),
            )]),
            AgentReply::Rejected,
            AgentReply::Rejected,
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_secs(2));

    assert!(outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p1".into(),
        status: "done".into(),
    }));
    assert!(outputs.contains(&MonitorOutput::Degraded {
        session: "work".into(),
        code: "agent_subscription_degraded".into(),
        message: "agent status subscription failed after snapshot retry".into(),
        uncovered_pane_ids: vec!["ws:p2".into()],
    }));
}

#[test]
fn panes_outside_assigned_set_report_degraded_live_coverage() {
    let fake = FakeHerdrSession::with_panes(["ws:p1", "ws:p2"]);
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let outputs = collect_for(&monitor, Duration::from_millis(500));

    assert!(outputs.contains(&MonitorOutput::Degraded {
        session: "work".into(),
        code: "agent_live_coverage_degraded".into(),
        message: "agent status events unavailable for 1 pane".into(),
        uncovered_pane_ids: vec!["ws:p2".into()],
    }));
}

#[test]
fn five_hundred_twelve_replayed_hints_cannot_mutate_snapshot_model() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")])],
        (0..512)
            .map(|index| {
                (
                    Duration::ZERO,
                    json!({"event":"pane_closed", "data":{"pane_id":format!("ghost-{index}")}}),
                )
            })
            .collect(),
        Vec::new(),
    );
    let outputs = run_monitor(&fake);

    assert_eq!(
        outputs
            .iter()
            .filter_map(|output| match output {
                MonitorOutput::Baseline { snapshot, .. }
                | MonitorOutput::Snapshot { snapshot, .. } => Some(
                    snapshot["panes"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|pane| pane["pane_id"].as_str().unwrap())
                        .collect::<Vec<_>>(),
                ),
                _ => None,
            })
            .collect::<Vec<_>>(),
        [vec!["ws:p1"], vec!["ws:p1"]]
    );
}

#[test]
fn structural_debounce_resets_after_each_hint() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")])],
        vec![
            (
                Duration::ZERO,
                json!({"event":"pane_updated", "data":{"pane":{"pane_id":"ws:p1"}}}),
            ),
            (
                Duration::from_millis(200),
                json!({"event":"layout_updated", "data":{"layout":{}}}),
            ),
        ],
        Vec::new(),
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);

    assert_eq!(
        monitor.recv_timeout(Duration::from_millis(380)),
        Err(std::sync::mpsc::RecvTimeoutError::Timeout)
    );
    assert!(matches!(
        monitor.recv_timeout(Duration::from_millis(400)).unwrap(),
        MonitorOutput::Snapshot { .. }
    ));
}

#[test]
fn uncovered_panes_reconcile_from_snapshot_every_five_seconds() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")]), snapshot([("ws:p1", "done")])],
        Vec::new(),
        Vec::new(),
    );
    let started = Instant::now();
    let (monitor, _baseline) = start_and_assign(&fake, &[]);
    let snapshot = loop {
        let output = monitor.recv_timeout(Duration::from_secs(6)).unwrap();
        if let MonitorOutput::Snapshot { snapshot, .. } = output {
            break snapshot;
        }
    };

    assert!(started.elapsed() >= Duration::from_millis(4800));
    assert_eq!(snapshot["panes"][0]["agent_status"], "done");
}

#[test]
fn structural_disconnect_terminates_monitor_with_closed_output() {
    let fake = FakeHerdrSession::scripted_with_structural_reply(
        [snapshot([("ws:p1", "idle")])],
        Vec::new(),
        Vec::new(),
        StructuralReply::Disconnect,
    );
    let monitor = SessionMonitor::start(fake.descriptor.clone()).unwrap();
    assert!(matches!(
        monitor.recv_timeout(Duration::from_secs(2)).unwrap(),
        MonitorOutput::Baseline { .. }
    ));
    let outputs = collect_for(&monitor, Duration::from_millis(500));

    assert!(outputs.contains(&MonitorOutput::Closed {
        session: "work".into(),
    }));
}

#[test]
fn early_monitor_error_closes_active_agent_reader_socket() {
    let probe_result = Arc::new(AtomicU64::new(0));
    let fake = FakeHerdrSession::scripted_with_structural_reply(
        [snapshot([("ws:p1", "idle")])],
        Vec::new(),
        vec![AgentReply::StartedProbingDisconnect(
            Duration::from_millis(500),
            Arc::clone(&probe_result),
        )],
        StructuralReply::DelayedDisconnect(Duration::from_millis(200)),
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline
        && !collect_for(&monitor, Duration::from_millis(25))
            .iter()
            .any(|output| matches!(output, MonitorOutput::Closed { .. }))
    {}
    let deadline = Instant::now() + Duration::from_secs(1);
    while Instant::now() < deadline && probe_result.load(Ordering::Acquire) == 0 {
        thread::sleep(Duration::from_millis(5));
    }

    assert_eq!(probe_result.load(Ordering::Acquire), 2);
}

#[test]
fn structural_subscription_failure_still_emits_a_baseline() {
    let fake = FakeHerdrSession::scripted_with_structural_reply(
        [snapshot([("ws:p1", "idle")])],
        Vec::new(),
        Vec::new(),
        StructuralReply::Rejected,
    );
    let monitor = SessionMonitor::start(fake.descriptor.clone()).unwrap();

    assert!(matches!(
        monitor.recv_timeout(Duration::from_secs(2)).unwrap(),
        MonitorOutput::Baseline { ref snapshot, .. }
            if snapshot["panes"][0]["pane_id"] == "ws:p1"
    ));
    assert!(matches!(
        monitor.recv_timeout(Duration::from_secs(2)).unwrap(),
        MonitorOutput::Degraded { ref code, .. } if code == "session_monitor_degraded"
    ));
    assert!(matches!(
        monitor.recv_timeout(Duration::from_secs(2)).unwrap(),
        MonitorOutput::Closed { .. }
    ));
}

#[test]
fn active_agent_disconnect_clears_live_coverage_and_degrades() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")])],
        Vec::new(),
        vec![AgentReply::Disconnected],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let outputs = collect_for(&monitor, Duration::from_millis(500));

    assert!(outputs.contains(&MonitorOutput::Degraded {
        session: "work".into(),
        code: "agent_subscription_degraded".into(),
        message: "agent status subscription ended".into(),
        uncovered_pane_ids: vec!["ws:p1".into()],
    }));
}

#[test]
fn repeated_reopen_failures_retry_with_backoff_until_coverage_is_restored() {
    let fake = FakeHerdrSession::scripted(
        [snapshot([("ws:p1", "idle")]), snapshot([("ws:p1", "idle")])],
        Vec::new(),
        vec![
            AgentReply::Disconnected,
            AgentReply::Rejected,
            AgentReply::Rejected,
            AgentReply::Started(Vec::new()),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let agent_requests = || {
        fake.requests()
            .iter()
            .filter(|request| {
                request["method"] == "events.subscribe"
                    && request["params"]["subscriptions"][0]["type"] == "pane.agent_status_changed"
            })
            .count()
    };
    let deadline = Instant::now() + Duration::from_secs(2);
    while agent_requests() < 3 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(agent_requests(), 3);
    thread::sleep(Duration::from_millis(100));
    assert_eq!(agent_requests(), 3, "retry spun without backoff");

    let outputs = collect_for(&monitor, Duration::from_secs(1));

    assert_eq!(agent_requests(), 4);
    assert!(outputs.iter().any(|output| matches!(
        output,
        MonitorOutput::Degraded { uncovered_pane_ids, .. } if uncovered_pane_ids.is_empty()
    )));
}

#[test]
fn pending_retry_keeps_deadline_and_reopens_only_final_assignment() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle"), ("ws:p3", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle"), ("ws:p3", "idle")]),
        ],
        Vec::new(),
        vec![
            AgentReply::Disconnected,
            AgentReply::Rejected,
            AgentReply::Rejected,
            AgentReply::Started(Vec::new()),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let agent_requests = || {
        fake.requests()
            .into_iter()
            .filter(|request| {
                request["method"] == "events.subscribe"
                    && request["params"]["subscriptions"][0]["type"] == "pane.agent_status_changed"
            })
            .collect::<Vec<_>>()
    };
    let deadline = Instant::now() + Duration::from_secs(2);
    while agent_requests().len() < 3 && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(agent_requests().len(), 3);

    let failure_observed = Instant::now();
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec!["ws:p2".into()]))
        .unwrap();
    thread::sleep(Duration::from_millis(50));
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec!["ws:p1".into()]))
        .unwrap();
    thread::sleep(Duration::from_millis(50));
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
            "ws:p3".into(),
        ]))
        .unwrap();
    assert_eq!(
        agent_requests().len(),
        3,
        "assignment bypassed retry backoff"
    );
    let preserved_deadline_check = failure_observed + Duration::from_millis(300);
    while Instant::now() < preserved_deadline_check {
        thread::sleep(Duration::from_millis(5));
    }

    let requests = agent_requests();
    assert_eq!(requests.len(), 4);
    assert_eq!(
        requests.last().unwrap()["params"]["subscriptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|subscription| subscription["pane_id"].as_str().unwrap())
            .collect::<Vec<_>>(),
        ["ws:p1", "ws:p2", "ws:p3"]
    );
    let outputs = collect_for(&monitor, Duration::from_millis(200));
    assert!(outputs.iter().any(|output| matches!(
        output,
        MonitorOutput::Degraded { uncovered_pane_ids, .. } if uncovered_pane_ids.is_empty()
    )));
}

#[test]
fn stalled_monitor_coalesces_command_flood_and_delivers_final_assignment() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle"), ("ws:p3", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle"), ("ws:p3", "idle")]),
        ],
        Vec::new(),
        vec![
            AgentReply::DelayedRejected(Duration::from_millis(300)),
            AgentReply::Rejected,
            AgentReply::Started(Vec::new()),
            AgentReply::Started(Vec::new()),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    let deadline = Instant::now() + Duration::from_secs(1);
    while fake
        .requests()
        .iter()
        .filter(|request| request["method"] == "events.subscribe")
        .count()
        < 2
        && Instant::now() < deadline
    {
        thread::sleep(Duration::from_millis(10));
    }
    for index in 0..1_000 {
        monitor
            .send(MonitorCommand::SetAgentPaneIds(vec![
                if index % 2 == 0 { "ws:p2" } else { "ws:p1" }.into(),
            ]))
            .unwrap();
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec!["ws:p3".into()]))
        .unwrap();

    let _outputs = collect_for(&monitor, Duration::from_secs(2));
    let agent_requests = fake
        .requests()
        .into_iter()
        .filter(|request| {
            request["method"] == "events.subscribe"
                && request["params"]["subscriptions"][0]["type"] == "pane.agent_status_changed"
        })
        .collect::<Vec<_>>();

    assert_eq!(
        agent_requests.len(),
        3,
        "intermediate assignments were queued"
    );
    assert_eq!(
        agent_requests.last().unwrap()["params"]["subscriptions"][0]["pane_id"],
        "ws:p3"
    );
}

#[test]
fn b_prime_disconnect_does_not_leave_old_b_counted_as_live() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![AgentReply::Started(Vec::new()), AgentReply::Disconnected],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_secs(2));

    assert!(outputs.contains(&MonitorOutput::Degraded {
        session: "work".into(),
        code: "agent_subscription_degraded".into(),
        message: "agent status subscription ended".into(),
        uncovered_pane_ids: vec!["ws:p1".into(), "ws:p2".into()],
    }));
}

#[test]
fn b_prime_transition_before_disconnect_is_not_dropped() {
    let fake = FakeHerdrSession::scripted(
        [
            snapshot([("ws:p1", "idle")]),
            snapshot([("ws:p1", "idle"), ("ws:p2", "idle")]),
        ],
        vec![(
            Duration::ZERO,
            json!({"event":"pane_created", "data":{"pane":{"pane_id":"ws:p2"}}}),
        )],
        vec![
            AgentReply::Started(Vec::new()),
            AgentReply::StartedThenDisconnected(vec![(
                Duration::ZERO,
                agent_event("ws:p1", "working"),
            )]),
        ],
    );
    let (monitor, _baseline) = start_and_assign(&fake, &["ws:p1"]);
    loop {
        let output = monitor.recv_timeout(Duration::from_secs(2)).unwrap();
        if matches!(output, MonitorOutput::Snapshot { .. }) {
            break;
        }
    }
    monitor
        .send(MonitorCommand::SetAgentPaneIds(vec![
            "ws:p1".into(),
            "ws:p2".into(),
        ]))
        .unwrap();
    let outputs = collect_for(&monitor, Duration::from_secs(2));

    assert!(outputs.contains(&MonitorOutput::AgentStatus {
        session: "work".into(),
        pane_id: "ws:p1".into(),
        status: "working".into(),
    }));
}
