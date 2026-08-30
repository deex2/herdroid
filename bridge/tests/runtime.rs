#[path = "../src/ipc.rs"]
#[allow(dead_code)]
mod ipc;
mod support;

use std::{
    io::{BufRead, BufReader, Read, Write},
    process::{Command, Stdio},
    sync::mpsc,
    thread,
    time::Duration,
};

use serde_json::{Value, json};
use support::fake_herdr::FakeHerdrBinary;

struct BridgeProcess {
    child: std::process::Child,
    output: mpsc::Receiver<Value>,
}

impl BridgeProcess {
    fn spawn(fake: &FakeHerdrBinary) -> Self {
        Self::spawn_with_child_secret(fake, None)
    }

    fn spawn_with_child_secret(fake: &FakeHerdrBinary, secret: Option<&str>) -> Self {
        let mut command = Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"));
        command
            .args(["--stdio", "--herdr-bin", fake.path.to_str().unwrap()])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(secret) = secret {
            command.env("HERDROID_CHILD_SECRET", secret);
        }
        let mut child = command.spawn().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (output_tx, output) = mpsc::channel();
        thread::spawn(move || {
            let mut lines = BufReader::new(stdout).lines();
            while let Some(Ok(line)) = lines.next() {
                let _ = output_tx.send(serde_json::from_str(&line).unwrap());
            }
        });
        Self { output, child }
    }

    fn shutdown_and_stderr(mut self) -> String {
        writeln!(
            self.child.stdin.as_mut().unwrap(),
            "{}",
            json!({"type":"shutdown","id":"done"})
        )
        .unwrap();
        assert!(self.child.wait().unwrap().success());
        let mut stderr = String::new();
        self.child
            .stderr
            .take()
            .unwrap()
            .read_to_string(&mut stderr)
            .unwrap();
        stderr
    }

    fn read(&mut self) -> Value {
        self.output
            .recv_timeout(Duration::from_secs(3))
            .expect("bridge output timeout")
    }

    fn read_for(&mut self, timeout: Duration) -> Value {
        self.output
            .recv_timeout(timeout)
            .expect("bridge output timeout")
    }

    fn wait_for_sessions(&mut self) -> Vec<Value> {
        loop {
            let value = self.read();
            if value["type"] == "sessions" {
                return value["sessions"].as_array().unwrap().clone();
            }
        }
    }

    fn wait_for_snapshots(&mut self) -> Vec<Value> {
        let mut values = Vec::new();
        while values.len() < 2 {
            let value = self.read();
            if value["type"] == "snapshot" {
                values.push(value);
            }
        }
        values
    }

    fn request(&mut self, session: &str, method: &str, params: Value) -> Value {
        self.request_for(session, method, params, Duration::from_secs(3))
    }

    fn request_for(
        &mut self,
        session: &str,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Value {
        writeln!(
            self.child.stdin.as_mut().unwrap(),
            "{}",
            json!({"type":"request","id":"r1","session":session,"method":method,"params":params})
        )
        .unwrap();
        loop {
            let value = self.read_for(timeout);
            if (value["type"] == "response" || value["type"] == "error") && value["id"] == "r1" {
                return value;
            }
        }
    }
}

#[cfg(unix)]
fn process_exists(pid: u32) -> bool {
    Command::new("kill")
        .args(["-0", &pid.to_string()])
        .status()
        .is_ok_and(|status| status.success())
}

#[cfg(windows)]
fn process_exists(pid: u32) -> bool {
    Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-Command",
            &format!(
                "if (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ exit 0 }} else {{ exit 1 }}"
            ),
        ])
        .status()
        .is_ok_and(|status| status.success())
}

#[test]
fn rediscovery_restarts_with_a_baseline_and_keeps_the_hello_epoch() {
    let fake = FakeHerdrBinary::sessions(["work"]);
    let mut bridge = BridgeProcess::spawn(&fake);
    let hello = bridge.read();
    bridge.wait_for_sessions();
    loop {
        if bridge.read_for(Duration::from_secs(3))["type"] == "snapshot" {
            break;
        }
    }
    fake.set_running([] as [&str; 0]);
    loop {
        if bridge.read_for(Duration::from_secs(7))["type"] == "sessions" {
            break;
        }
    }
    fake.set_running(["work"]);
    let snapshot = loop {
        let value = bridge.read_for(Duration::from_secs(7));
        if value["type"] == "snapshot" && value["baseline"] == true {
            break value;
        }
    };
    assert_eq!(snapshot["epoch"], hello["epoch"]);
}

#[test]
fn malformed_and_timed_out_discovery_keep_the_last_good_session_state() {
    let fake = FakeHerdrBinary::sessions(["work"]);
    let mut bridge = BridgeProcess::spawn(&fake);
    bridge.read();
    let original_sessions = bridge.wait_for_sessions();
    assert_eq!(
        original_sessions,
        vec![json!({
            "name": "work",
            "running": true,
            "socket_path": original_sessions[0]["socket_path"],
        })]
    );
    let original_snapshot = loop {
        let message = bridge.read_for(Duration::from_secs(3));
        if message["type"] == "snapshot" {
            break message["snapshot"].clone();
        }
    };
    fake.set_list_raw("not json");
    assert!(bridge.child.try_wait().unwrap().is_none());
    thread::sleep(Duration::from_secs(6));
    assert!(bridge.child.try_wait().unwrap().is_none());
    let reconciliation = bridge.read_for(Duration::from_millis(200));
    assert_eq!(reconciliation["type"], "snapshot");
    assert_eq!(reconciliation["session"], "work");
    assert_eq!(reconciliation["snapshot"], original_snapshot);
    let malformed_response = bridge.request("work", "session.snapshot", json!({}));
    assert_eq!(malformed_response["session"], "work");
    assert_eq!(malformed_response["result"]["snapshot"], original_snapshot);

    fake.set_list_timeout(true);
    let timeout_pid = fake.wait_for_timeout_pid();
    assert!(process_exists(timeout_pid));
    let timeout_response = bridge.request_for(
        "work",
        "session.snapshot",
        json!({}),
        Duration::from_secs(7),
    );
    assert_eq!(timeout_response["session"], "work");
    assert_eq!(timeout_response["result"]["snapshot"], original_snapshot);
    assert!(
        !process_exists(timeout_pid),
        "timed-out child was not reaped"
    );
    assert!(bridge.child.try_wait().unwrap().is_none());
    fake.set_list_timeout(false);
    fake.set_running([] as [&str; 0]);
    let removed = loop {
        let value = bridge.read_for(Duration::from_secs(7));
        if value["type"] == "sessions" {
            break value;
        }
    };
    assert_eq!(removed["sessions"], json!([]));

    fake.set_running(["work"]);
    let restored = loop {
        let value = bridge.read_for(Duration::from_secs(7));
        if value["type"] == "sessions" {
            break value;
        }
    };
    assert_eq!(restored["sessions"], Value::Array(original_sessions));
}

#[test]
fn one_stdio_process_multiplexes_two_sessions() {
    let fake = FakeHerdrBinary::sessions(["default", "work"]);
    let mut bridge = BridgeProcess::spawn(&fake);
    let hello = bridge.read();
    assert_eq!(hello["type"], "hello");
    assert_eq!(bridge.wait_for_sessions().len(), 2);
    let snapshots = bridge.wait_for_snapshots();
    assert!(
        snapshots
            .iter()
            .all(|snapshot| snapshot["epoch"] == hello["epoch"])
    );
    let response = bridge.request("work", "session.snapshot", json!({}));
    assert!(response["result"]["snapshot"].get("workspaces").is_some());
}

#[test]
fn stock_herdr_error_is_forwarded_unchanged() {
    let fake = FakeHerdrBinary::sessions(["work"]);
    let mut bridge = BridgeProcess::spawn(&fake);
    bridge.read();
    bridge.wait_for_sessions();
    let response = bridge.request("work", "pane.close", json!({"pane_id":"pane_1"}));

    assert_eq!(response["type"], "error");
    assert_eq!(response["code"], "pane_not_found");
    assert_eq!(response["message"], "pane pane_1 not found");
}

#[test]
fn failure_paths_never_write_request_snapshot_or_environment_contents_to_stderr() {
    const REQUEST_MARKER: &str = "request-secret-4f9821";
    const SNAPSHOT_MARKER: &str = "snapshot-secret-b8730a";
    const ENV_MARKER: &str = "environment-secret-235dc6";
    let fake = FakeHerdrBinary::sessions_with_snapshot_marker(["work"], SNAPSHOT_MARKER);
    let mut bridge = BridgeProcess::spawn_with_child_secret(&fake, Some(ENV_MARKER));
    bridge.read();
    bridge.wait_for_sessions();
    loop {
        let message = bridge.read();
        if message["type"] == "snapshot" {
            assert_eq!(message["snapshot"]["terminal_bytes"], SNAPSHOT_MARKER);
            break;
        }
    }
    let error = bridge.request(
        "work",
        "pane.close",
        json!({"pane_id":"pane_1","terminal_bytes":REQUEST_MARKER}),
    );
    assert_eq!(error["code"], "pane_not_found");
    fake.set_list_timeout(true);
    thread::sleep(Duration::from_secs(11));
    let stderr = bridge.shutdown_and_stderr();
    for marker in [REQUEST_MARKER, SNAPSHOT_MARKER, ENV_MARKER] {
        assert!(!stderr.contains(marker), "stderr leaked {marker}: {stderr}");
    }

    let mut broken = Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"))
        .args(["--stdio", "--herdr-bin", fake.path.to_str().unwrap()])
        .env("HERDROID_CHILD_SECRET", ENV_MARKER)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    drop(broken.stdout.take());
    let output = broken.wait_with_output().unwrap();
    assert_eq!(output.status.code(), Some(3));
    let stderr = String::from_utf8(output.stderr).unwrap();
    for marker in [REQUEST_MARKER, SNAPSHOT_MARKER, ENV_MARKER] {
        assert!(!stderr.contains(marker), "stderr leaked {marker}: {stderr}");
    }
}
