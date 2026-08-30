use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    io,
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicBool, Ordering},
        mpsc::{
            self, Receiver, RecvTimeoutError, SendError, SyncSender, TryRecvError, TrySendError,
        },
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use serde_json::{Value, json};

use crate::{
    herdr::{SubscriptionReader, api_call_until},
    ipc::ApiEndpoint,
    protocol::SessionDescriptor,
};

pub const STRUCTURAL_DEBOUNCE: Duration = Duration::from_millis(300);
pub const PANE_SET_DEBOUNCE: Duration = Duration::from_secs(1);
pub const RECONCILE_INTERVAL: Duration = Duration::from_secs(5);
const AGENT_RETRY_BACKOFF: Duration = Duration::from_millis(250);
const MONITOR_CHANNEL_CAPACITY: usize = 64;

const STRUCTURAL_SUBSCRIPTIONS: &[&str] = &[
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
    "layout.updated",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MonitorCommand {
    SetAgentPaneIds(Vec<String>),
}

#[derive(Clone, Debug, PartialEq)]
pub enum MonitorOutput {
    Baseline {
        session: String,
        snapshot: Value,
    },
    Snapshot {
        session: String,
        snapshot: Value,
    },
    AgentStatus {
        session: String,
        pane_id: String,
        status: String,
    },
    Degraded {
        session: String,
        code: String,
        message: String,
        uncovered_pane_ids: Vec<String>,
    },
    Closed {
        session: String,
    },
}

pub struct SessionMonitor {
    commands: MonitorCommandSender,
    outputs: Receiver<MonitorOutput>,
    cancelled: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}

impl SessionMonitor {
    pub fn start(session: SessionDescriptor) -> io::Result<Self> {
        let endpoint = ApiEndpoint::from_reported_path(&session.socket_path)?;
        let (command_tx, command_rx) = monitor_command_channel();
        let cancelled = Arc::new(AtomicBool::new(false));
        let (output_tx, output_rx) = mpsc::sync_channel(MONITOR_CHANNEL_CAPACITY);
        let output_tx = BoundedSender::new(output_tx, Arc::clone(&cancelled));
        let worker_cancelled = Arc::clone(&cancelled);
        let worker =
            thread::spawn(move || run(session, endpoint, command_rx, output_tx, worker_cancelled));
        Ok(Self {
            commands: command_tx,
            outputs: output_rx,
            cancelled,
            worker: Some(worker),
        })
    }

    pub fn send(&self, command: MonitorCommand) -> Result<(), SendError<MonitorCommand>> {
        self.commands.send(command)
    }

    #[allow(dead_code)]
    pub(crate) fn command_sender(&self) -> MonitorCommandSender {
        self.commands.clone()
    }

    #[allow(dead_code)]
    pub fn cancellation(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancelled)
    }

    pub fn recv_timeout(&self, timeout: Duration) -> Result<MonitorOutput, RecvTimeoutError> {
        self.outputs.recv_timeout(timeout)
    }
}

#[derive(Clone)]
pub(crate) struct MonitorCommandSender {
    sender: SyncSender<MonitorCommand>,
    receiver: Weak<Mutex<Receiver<MonitorCommand>>>,
}

pub(crate) struct MonitorCommandReceiver {
    receiver: Arc<Mutex<Receiver<MonitorCommand>>>,
}

pub(crate) fn monitor_command_channel() -> (MonitorCommandSender, MonitorCommandReceiver) {
    let (sender, receiver) = mpsc::sync_channel(1);
    let receiver = Arc::new(Mutex::new(receiver));
    (
        MonitorCommandSender {
            sender,
            receiver: Arc::downgrade(&receiver),
        },
        MonitorCommandReceiver { receiver },
    )
}

impl MonitorCommandSender {
    pub(crate) fn send(
        &self,
        mut command: MonitorCommand,
    ) -> Result<(), SendError<MonitorCommand>> {
        loop {
            match self.sender.try_send(command) {
                Ok(()) => return Ok(()),
                Err(TrySendError::Full(returned)) => {
                    command = returned;
                    let Some(receiver) = self.receiver.upgrade() else {
                        return Err(SendError(command));
                    };
                    let _ = receiver.lock().unwrap().try_recv();
                }
                Err(TrySendError::Disconnected(returned)) => return Err(SendError(returned)),
            }
        }
    }
}

impl MonitorCommandReceiver {
    fn try_recv(&self) -> Result<MonitorCommand, TryRecvError> {
        self.receiver.lock().unwrap().try_recv()
    }

    #[cfg(test)]
    #[allow(dead_code)]
    pub(crate) fn recv(&self) -> Result<MonitorCommand, mpsc::RecvError> {
        self.receiver.lock().unwrap().recv()
    }

    #[cfg(test)]
    #[allow(dead_code)]
    pub(crate) fn recv_timeout(
        &self,
        timeout: Duration,
    ) -> Result<MonitorCommand, RecvTimeoutError> {
        self.receiver.lock().unwrap().recv_timeout(timeout)
    }
}

struct BoundedSender<T> {
    sender: SyncSender<T>,
    cancelled: Arc<AtomicBool>,
}

impl<T> Clone for BoundedSender<T> {
    fn clone(&self) -> Self {
        Self {
            sender: self.sender.clone(),
            cancelled: Arc::clone(&self.cancelled),
        }
    }
}

impl<T> BoundedSender<T> {
    fn new(sender: SyncSender<T>, cancelled: Arc<AtomicBool>) -> Self {
        Self { sender, cancelled }
    }

    fn send(&self, mut value: T) -> Result<(), SendError<T>> {
        loop {
            match self.sender.try_send(value) {
                Ok(()) => return Ok(()),
                Err(TrySendError::Full(returned)) => {
                    value = returned;
                    if self.cancelled.load(Ordering::Acquire) {
                        return Err(SendError(value));
                    }
                    thread::sleep(Duration::from_millis(5));
                }
                Err(TrySendError::Disconnected(returned)) => return Err(SendError(returned)),
            }
        }
    }
}

impl Drop for SessionMonitor {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

enum ReaderMessage {
    Structural,
    Agent {
        generation: u64,
        event: Value,
    },
    Ended {
        reader: ReaderKind,
        expected: bool,
        message: String,
    },
}

#[derive(Clone, Copy)]
enum ReaderKind {
    Structural,
    Agent(u64),
}

struct ActiveAgentSubscription {
    generation: u64,
    pane_ids: BTreeSet<String>,
    cancelled: Arc<AtomicBool>,
}

impl Drop for ActiveAgentSubscription {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

struct AgentCutover {
    old_generation: u64,
    new_generation: u64,
    old_pairs: VecDeque<(String, String)>,
    buffered_new: Vec<Value>,
    old_ended: bool,
    deadline: Instant,
}

struct OverlapDedupe {
    generation: u64,
    pairs: VecDeque<(String, String)>,
    deadline: Instant,
}

fn run(
    session: SessionDescriptor,
    endpoint: ApiEndpoint,
    commands: MonitorCommandReceiver,
    outputs: BoundedSender<MonitorOutput>,
    cancelled: Arc<AtomicBool>,
) {
    let result = run_inner(
        &session,
        &endpoint,
        &commands,
        &outputs,
        Arc::clone(&cancelled),
    );
    if let Err(error) = result {
        let mut request_number = 0;
        if let Ok(snapshot) = fetch_snapshot(&endpoint, &mut request_number, &cancelled) {
            let _ = outputs.send(MonitorOutput::Baseline {
                session: session.name.clone(),
                snapshot,
            });
        }
        let _ = outputs.send(MonitorOutput::Degraded {
            session: session.name.clone(),
            code: "session_monitor_degraded".into(),
            message: error.to_string(),
            uncovered_pane_ids: Vec::new(),
        });
    }
    let _ = outputs.send(MonitorOutput::Closed {
        session: session.name,
    });
}

fn run_inner(
    session: &SessionDescriptor,
    endpoint: &ApiEndpoint,
    commands: &MonitorCommandReceiver,
    outputs: &BoundedSender<MonitorOutput>,
    cancelled: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut request_number = 0_u64;
    let mut structural = SubscriptionReader::open_until(
        endpoint,
        &subscription_request(
            "monitor-structural",
            STRUCTURAL_SUBSCRIPTIONS
                .iter()
                .map(|event| json!({"type":event}))
                .collect(),
        ),
        &cancelled,
        Instant::now() + crate::herdr::API_CALL_TIMEOUT,
    )?;
    structural
        .next(&cancelled)?
        .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "structural_closed"))?;

    let (reader_tx, reader_rx) = mpsc::sync_channel(MONITOR_CHANNEL_CAPACITY);
    let reader_tx = BoundedSender::new(reader_tx, Arc::clone(&cancelled));
    spawn_reader(
        structural,
        Arc::clone(&cancelled),
        reader_tx.clone(),
        ReaderKind::Structural,
    );

    let mut snapshot = fetch_snapshot(endpoint, &mut request_number, &cancelled)?;
    let mut snapshot_panes = pane_ids(&snapshot);
    let mut statuses = snapshot_statuses(&snapshot);
    outputs
        .send(MonitorOutput::Baseline {
            session: session.name.clone(),
            snapshot: snapshot.clone(),
        })
        .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "monitor_output_closed"))?;

    let mut structural_deadline = None;
    let mut reconcile_deadline = Instant::now() + RECONCILE_INTERVAL;
    let mut assigned_panes = BTreeSet::new();
    let mut active_agent: Option<ActiveAgentSubscription> = None;
    let mut pending_agent: Option<(Instant, BTreeSet<String>)> = None;
    let mut next_generation = 0_u64;
    let mut cutover: Option<AgentCutover> = None;
    let mut overlap_dedupe: Option<OverlapDedupe> = None;
    let mut last_reported_uncovered = Vec::new();
    while !cancelled.load(Ordering::Acquire) {
        while let Ok(command) = commands.try_recv() {
            let MonitorCommand::SetAgentPaneIds(pane_ids) = command;
            assigned_panes = pane_ids.into_iter().collect();
            let desired = desired_panes(&assigned_panes, &snapshot_panes);
            if active_agent.is_none() {
                if let Some((_, pending_panes)) = pending_agent.as_mut() {
                    if desired.is_empty() {
                        pending_agent = None;
                    } else {
                        *pending_panes = desired;
                    }
                } else {
                    replace_agent_subscription(
                        session,
                        endpoint,
                        &mut request_number,
                        &assigned_panes,
                        &mut snapshot,
                        &mut snapshot_panes,
                        &mut statuses,
                        &mut active_agent,
                        &mut next_generation,
                        &mut cutover,
                        Arc::clone(&cancelled),
                        &reader_tx,
                        outputs,
                    );
                    schedule_if_changed(
                        &assigned_panes,
                        &snapshot_panes,
                        active_agent.as_ref(),
                        &mut pending_agent,
                    );
                }
            } else if active_agent
                .as_ref()
                .is_some_and(|active| active.pane_ids != desired)
            {
                pending_agent = Some((Instant::now() + PANE_SET_DEBOUNCE, desired));
            } else {
                pending_agent = None;
            }
            report_uncovered(
                session,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut last_reported_uncovered,
                outputs,
            );
        }

        while let Ok(message) = reader_rx.try_recv() {
            match message {
                ReaderMessage::Structural => {
                    structural_deadline = Some(Instant::now() + STRUCTURAL_DEBOUNCE);
                }
                ReaderMessage::Agent { generation, event } => {
                    if let Some(cutover) = cutover.as_mut() {
                        if generation == cutover.old_generation {
                            if let Some(pair) = status_pair(&event) {
                                cutover.old_pairs.push_back(pair);
                            }
                            emit_agent_status(session, event, &mut statuses, outputs);
                        } else if generation == cutover.new_generation {
                            cutover.buffered_new.push(event);
                        }
                    } else if active_agent
                        .as_ref()
                        .is_some_and(|active| active.generation == generation)
                        && !deduplicate_overlap(generation, &event, &mut overlap_dedupe)
                    {
                        emit_agent_status(session, event, &mut statuses, outputs);
                    }
                }
                ReaderMessage::Ended {
                    reader: ReaderKind::Structural,
                    expected: false,
                    message,
                } => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, message)),
                ReaderMessage::Ended {
                    reader: ReaderKind::Agent(generation),
                    ..
                } if cutover.as_mut().is_some_and(|cutover| {
                    if cutover.old_generation == generation {
                        cutover.old_ended = true;
                        true
                    } else {
                        false
                    }
                }) => {}
                ReaderMessage::Ended {
                    reader: ReaderKind::Agent(generation),
                    expected: false,
                    ..
                } if active_agent
                    .as_ref()
                    .is_some_and(|active| active.generation == generation) =>
                {
                    if cutover
                        .as_ref()
                        .is_some_and(|cutover| cutover.new_generation == generation)
                    {
                        finish_cutover(
                            session,
                            cutover.take().unwrap(),
                            &mut overlap_dedupe,
                            &mut statuses,
                            outputs,
                        );
                    }
                    active_agent = None;
                    overlap_dedupe = None;
                    let uncovered = uncovered_panes(&snapshot_panes, None);
                    last_reported_uncovered = uncovered.clone();
                    let _ = outputs.send(MonitorOutput::Degraded {
                        session: session.name.clone(),
                        code: "agent_subscription_degraded".into(),
                        message: "agent status subscription ended".into(),
                        uncovered_pane_ids: uncovered,
                    });
                    pending_agent = Some((
                        Instant::now(),
                        desired_panes(&assigned_panes, &snapshot_panes),
                    ));
                }
                ReaderMessage::Ended { .. } => {}
            }
        }

        if cutover
            .as_ref()
            .is_some_and(|cutover| cutover.old_ended || Instant::now() >= cutover.deadline)
        {
            finish_cutover(
                session,
                cutover.take().unwrap(),
                &mut overlap_dedupe,
                &mut statuses,
                outputs,
            );
        }
        if overlap_dedupe
            .as_ref()
            .is_some_and(|dedupe| Instant::now() >= dedupe.deadline)
        {
            overlap_dedupe = None;
        }

        let now = Instant::now();
        if structural_deadline.is_some_and(|deadline| now >= deadline) {
            refresh_snapshot(
                session,
                endpoint,
                &mut request_number,
                &mut snapshot,
                &mut snapshot_panes,
                &mut statuses,
                active_agent.as_ref(),
                &cancelled,
                outputs,
            )?;
            structural_deadline = None;
            reconcile_deadline = Instant::now() + RECONCILE_INTERVAL;
            schedule_if_changed(
                &assigned_panes,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut pending_agent,
            );
            report_uncovered(
                session,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut last_reported_uncovered,
                outputs,
            );
        } else if now >= reconcile_deadline {
            refresh_snapshot(
                session,
                endpoint,
                &mut request_number,
                &mut snapshot,
                &mut snapshot_panes,
                &mut statuses,
                active_agent.as_ref(),
                &cancelled,
                outputs,
            )?;
            reconcile_deadline = Instant::now() + RECONCILE_INTERVAL;
            schedule_if_changed(
                &assigned_panes,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut pending_agent,
            );
        }

        if pending_agent
            .as_ref()
            .is_some_and(|(deadline, _)| Instant::now() >= *deadline)
        {
            pending_agent = None;
            replace_agent_subscription(
                session,
                endpoint,
                &mut request_number,
                &assigned_panes,
                &mut snapshot,
                &mut snapshot_panes,
                &mut statuses,
                &mut active_agent,
                &mut next_generation,
                &mut cutover,
                Arc::clone(&cancelled),
                &reader_tx,
                outputs,
            );
            schedule_if_changed(
                &assigned_panes,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut pending_agent,
            );
            report_uncovered(
                session,
                &snapshot_panes,
                active_agent.as_ref(),
                &mut last_reported_uncovered,
                outputs,
            );
        }
        thread::sleep(Duration::from_millis(10));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn replace_agent_subscription(
    session: &SessionDescriptor,
    endpoint: &ApiEndpoint,
    request_number: &mut u64,
    assigned_panes: &BTreeSet<String>,
    snapshot: &mut Value,
    snapshot_panes: &mut BTreeSet<String>,
    statuses: &mut HashMap<String, String>,
    active: &mut Option<ActiveAgentSubscription>,
    next_generation: &mut u64,
    cutover: &mut Option<AgentCutover>,
    cancelled: Arc<AtomicBool>,
    reader_tx: &BoundedSender<ReaderMessage>,
    outputs: &BoundedSender<MonitorOutput>,
) {
    let mut desired = desired_panes(assigned_panes, snapshot_panes);
    if desired.is_empty() {
        drop(active.take());
        return;
    }

    let first = open_agent(endpoint, request_number, &desired, &cancelled);
    let reader = match first {
        Ok(reader) => reader,
        Err(_) => {
            let refreshed = refresh_snapshot(
                session,
                endpoint,
                request_number,
                snapshot,
                snapshot_panes,
                statuses,
                active.as_ref(),
                &cancelled,
                outputs,
            );
            if refreshed.is_err() {
                return;
            }
            desired = desired_panes(assigned_panes, snapshot_panes);
            match open_agent(endpoint, request_number, &desired, &cancelled) {
                Ok(reader) => reader,
                Err(_) => {
                    let uncovered = uncovered_panes(snapshot_panes, active.as_ref());
                    let _ = outputs.send(MonitorOutput::Degraded {
                        session: session.name.clone(),
                        code: "agent_subscription_degraded".into(),
                        message: "agent status subscription failed after snapshot retry".into(),
                        uncovered_pane_ids: uncovered,
                    });
                    return;
                }
            }
        }
    };

    let reader_cancelled = Arc::new(AtomicBool::new(false));
    *next_generation += 1;
    let generation = *next_generation;
    spawn_reader(
        reader,
        Arc::clone(&reader_cancelled),
        reader_tx.clone(),
        ReaderKind::Agent(generation),
    );
    let replacement = ActiveAgentSubscription {
        generation,
        pane_ids: desired,
        cancelled: reader_cancelled,
    };
    if let Some(old) = active.replace(replacement) {
        *cutover = Some(AgentCutover {
            old_generation: old.generation,
            new_generation: generation,
            old_pairs: VecDeque::new(),
            buffered_new: Vec::new(),
            old_ended: false,
            deadline: Instant::now() + STRUCTURAL_DEBOUNCE,
        });
    }
}

fn emit_agent_status(
    session: &SessionDescriptor,
    event: Value,
    statuses: &mut HashMap<String, String>,
    outputs: &BoundedSender<MonitorOutput>,
) {
    let Some((pane_id, status)) = status_pair(&event) else {
        return;
    };
    if statuses.get(&pane_id) == Some(&status) {
        return;
    }
    statuses.insert(pane_id.clone(), status.clone());
    let _ = outputs.send(MonitorOutput::AgentStatus {
        session: session.name.clone(),
        pane_id,
        status,
    });
}

fn status_pair(event: &Value) -> Option<(String, String)> {
    Some((
        event["data"]["pane_id"].as_str()?.to_owned(),
        event["data"]["agent_status"].as_str()?.to_owned(),
    ))
}

fn finish_cutover(
    session: &SessionDescriptor,
    mut cutover: AgentCutover,
    overlap_dedupe: &mut Option<OverlapDedupe>,
    statuses: &mut HashMap<String, String>,
    outputs: &BoundedSender<MonitorOutput>,
) {
    for event in cutover.buffered_new {
        if status_pair(&event).is_some_and(|pair| cutover.old_pairs.front() == Some(&pair)) {
            cutover.old_pairs.pop_front();
        } else {
            cutover.old_pairs.clear();
            emit_agent_status(session, event, statuses, outputs);
        }
    }
    if !cutover.old_pairs.is_empty() {
        *overlap_dedupe = Some(OverlapDedupe {
            generation: cutover.new_generation,
            pairs: cutover.old_pairs,
            deadline: cutover.deadline,
        });
    }
}

fn deduplicate_overlap(
    generation: u64,
    event: &Value,
    overlap_dedupe: &mut Option<OverlapDedupe>,
) -> bool {
    let Some(dedupe) = overlap_dedupe.as_mut() else {
        return false;
    };
    if dedupe.generation != generation || Instant::now() >= dedupe.deadline {
        *overlap_dedupe = None;
        return false;
    }
    if status_pair(event).is_some_and(|pair| dedupe.pairs.front() == Some(&pair)) {
        dedupe.pairs.pop_front();
        if dedupe.pairs.is_empty() {
            *overlap_dedupe = None;
        }
        true
    } else {
        *overlap_dedupe = None;
        false
    }
}

fn open_agent(
    endpoint: &ApiEndpoint,
    request_number: &mut u64,
    pane_ids: &BTreeSet<String>,
    cancelled: &AtomicBool,
) -> io::Result<SubscriptionReader> {
    let mut agent = SubscriptionReader::open_until(
        endpoint,
        &agent_request(request_number, pane_ids.iter()),
        cancelled,
        Instant::now() + crate::herdr::API_CALL_TIMEOUT,
    )?;
    agent
        .next(cancelled)?
        .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "agent_subscription_closed"))?;
    Ok(agent)
}

#[allow(clippy::too_many_arguments)]
fn refresh_snapshot(
    session: &SessionDescriptor,
    endpoint: &ApiEndpoint,
    request_number: &mut u64,
    snapshot: &mut Value,
    snapshot_panes: &mut BTreeSet<String>,
    statuses: &mut HashMap<String, String>,
    active: Option<&ActiveAgentSubscription>,
    cancelled: &AtomicBool,
    outputs: &BoundedSender<MonitorOutput>,
) -> io::Result<()> {
    *snapshot = fetch_snapshot(endpoint, request_number, cancelled)?;
    *snapshot_panes = pane_ids(snapshot);
    statuses.retain(|pane_id, _| snapshot_panes.contains(pane_id));
    for (pane_id, status) in snapshot_statuses(snapshot) {
        if active.is_none_or(|active| !active.pane_ids.contains(&pane_id)) {
            statuses.insert(pane_id, status);
        } else {
            statuses.entry(pane_id).or_insert(status);
        }
    }
    outputs
        .send(MonitorOutput::Snapshot {
            session: session.name.clone(),
            snapshot: snapshot.clone(),
        })
        .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "monitor_output_closed"))
}

fn schedule_if_changed(
    assigned: &BTreeSet<String>,
    snapshot_panes: &BTreeSet<String>,
    active: Option<&ActiveAgentSubscription>,
    pending: &mut Option<(Instant, BTreeSet<String>)>,
) {
    let desired = desired_panes(assigned, snapshot_panes);
    if desired.is_empty() || active.is_some_and(|active| active.pane_ids == desired) {
        *pending = None;
    } else if pending
        .as_ref()
        .is_none_or(|(_, pending_panes)| pending_panes != &desired)
    {
        let backoff = if active.is_none() {
            AGENT_RETRY_BACKOFF
        } else {
            PANE_SET_DEBOUNCE
        };
        *pending = Some((Instant::now() + backoff, desired));
    }
}

fn report_uncovered(
    session: &SessionDescriptor,
    snapshot_panes: &BTreeSet<String>,
    active: Option<&ActiveAgentSubscription>,
    last_reported: &mut Vec<String>,
    outputs: &BoundedSender<MonitorOutput>,
) {
    let uncovered = uncovered_panes(snapshot_panes, active);
    if *last_reported == uncovered || (uncovered.is_empty() && last_reported.is_empty()) {
        return;
    }
    *last_reported = uncovered.clone();
    let _ = outputs.send(MonitorOutput::Degraded {
        session: session.name.clone(),
        code: "agent_live_coverage_degraded".into(),
        message: if uncovered.is_empty() {
            "agent status live coverage restored".into()
        } else {
            format!(
                "agent status events unavailable for {} pane{}",
                uncovered.len(),
                if uncovered.len() == 1 { "" } else { "s" }
            )
        },
        uncovered_pane_ids: uncovered,
    });
}

fn uncovered_panes(
    snapshot_panes: &BTreeSet<String>,
    active: Option<&ActiveAgentSubscription>,
) -> Vec<String> {
    let covered = active.map(|active| &active.pane_ids);
    snapshot_panes
        .iter()
        .filter(|pane_id| covered.is_none_or(|covered| !covered.contains(*pane_id)))
        .cloned()
        .collect()
}

fn desired_panes(
    assigned: &BTreeSet<String>,
    snapshot_panes: &BTreeSet<String>,
) -> BTreeSet<String> {
    assigned.intersection(snapshot_panes).cloned().collect()
}

fn fetch_snapshot(
    endpoint: &ApiEndpoint,
    request_number: &mut u64,
    cancelled: &AtomicBool,
) -> io::Result<Value> {
    *request_number += 1;
    let response = api_call_until(
        endpoint,
        &json!({
            "id":format!("monitor-snapshot-{request_number}"),
            "method":"session.snapshot",
            "params":{}
        }),
        cancelled,
        Instant::now() + crate::herdr::API_CALL_TIMEOUT,
    )?;
    response
        .get("result")
        .and_then(|result| result.get("snapshot").or(Some(result)))
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "snapshot_result_missing"))
}

fn agent_request<'a>(
    request_number: &mut u64,
    pane_ids: impl IntoIterator<Item = &'a String>,
) -> Value {
    *request_number += 1;
    subscription_request(
        &format!("monitor-agent-{request_number}"),
        pane_ids
            .into_iter()
            .map(|pane_id| json!({"type":"pane.agent_status_changed", "pane_id":pane_id}))
            .collect(),
    )
}

fn subscription_request(id: &str, subscriptions: Vec<Value>) -> Value {
    json!({
        "id":id,
        "method":"events.subscribe",
        "params":{"subscriptions":subscriptions}
    })
}

fn spawn_reader(
    mut reader: SubscriptionReader,
    cancelled: Arc<AtomicBool>,
    messages: BoundedSender<ReaderMessage>,
    reader_kind: ReaderKind,
) {
    thread::spawn(move || {
        loop {
            match reader.next(&cancelled) {
                Ok(Some(event)) => {
                    if messages
                        .send(match reader_kind {
                            ReaderKind::Agent(generation) => {
                                ReaderMessage::Agent { generation, event }
                            }
                            ReaderKind::Structural => ReaderMessage::Structural,
                        })
                        .is_err()
                    {
                        return;
                    }
                }
                result => {
                    let message = match result {
                        Ok(None) => "subscription closed".into(),
                        Err(error) => error.to_string(),
                        Ok(Some(_)) => unreachable!(),
                    };
                    let _ = messages.send(ReaderMessage::Ended {
                        reader: reader_kind,
                        expected: cancelled.load(Ordering::Acquire),
                        message,
                    });
                    return;
                }
            }
        }
    });
}

fn pane_ids(snapshot: &Value) -> BTreeSet<String> {
    snapshot["panes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|pane| pane["pane_id"].as_str().map(str::to_owned))
        .collect()
}

fn snapshot_statuses(snapshot: &Value) -> HashMap<String, String> {
    snapshot["panes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|pane| {
            Some((
                pane["pane_id"].as_str()?.to_owned(),
                pane["agent_status"].as_str()?.to_owned(),
            ))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;

    #[test]
    fn latest_command_sender_observes_concurrent_receiver_drop() {
        for _ in 0..100 {
            let (sender, receiver) = monitor_command_channel();
            sender
                .send(MonitorCommand::SetAgentPaneIds(vec!["first".into()]))
                .unwrap();
            let start = Arc::new(Barrier::new(3));
            let sender_start = Arc::clone(&start);
            let drop_start = Arc::clone(&start);
            let (result_tx, result_rx) = mpsc::channel();
            let sending = thread::spawn(move || {
                sender_start.wait();
                let raced = sender.send(MonitorCommand::SetAgentPaneIds(vec!["second".into()]));
                result_tx.send((sender, raced)).unwrap();
            });
            let dropping = thread::spawn(move || {
                drop_start.wait();
                drop(receiver);
            });
            start.wait();

            let (sender, _raced) = result_rx
                .recv_timeout(Duration::from_secs(1))
                .expect("send hung while receiver dropped");
            dropping.join().unwrap();
            assert!(
                sender
                    .send(MonitorCommand::SetAgentPaneIds(vec!["after-drop".into()]))
                    .is_err()
            );
            sending.join().unwrap();
        }
    }
}
