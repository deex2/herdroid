#[path = "../src/protocol.rs"]
mod protocol;

use std::{
    fs,
    io::{BufRead, BufReader, Cursor, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};

use protocol::{
    ClientMessage, MAX_LINE_BYTES, PROTOCOL_VERSION, ServerMessage, SessionDescriptor,
    read_client_message, write_server_message,
};
use serde_json::json;

fn decode(message: &ClientMessage) -> ClientMessage {
    let mut bytes = serde_json::to_vec(message).unwrap();
    bytes.push(b'\n');
    read_client_message(&mut BufReader::new(Cursor::new(bytes))).unwrap()
}

fn fake_herdr(version: &str) -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "herdroid-bridge-test-{}-{}{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos(),
        if cfg!(windows) { ".cmd" } else { ".sh" }
    ));
    #[cfg(windows)]
    fs::write(&path, format!("@echo off\r\nif \"%1\"==\"--version\" echo herdr {version}\r\nif \"%1\"==\"session\" echo {{\"sessions\":[]}}\r\n")).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;

        fs::write(&path, format!("#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then printf 'herdr {version}\\n'; else printf '{{\"sessions\":[]}}\\n'; fi\n")).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    }
    path
}

fn stdio_status(herdr: &Path) -> Option<i32> {
    Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"))
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::null())
        .status()
        .unwrap()
        .code()
}

#[derive(Default)]
struct FlushTrackingWriter {
    bytes: Vec<u8>,
    flushed: bool,
}

impl Write for FlushTrackingWriter {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.flushed = true;
        Ok(())
    }
}

#[test]
fn rejects_a_line_over_one_mebibyte() {
    let input = format!("{}\n", "x".repeat(MAX_LINE_BYTES + 1));
    let error = read_client_message(&mut BufReader::new(input.as_bytes())).unwrap_err();
    assert_eq!(error.code(), "line_too_large");
}

#[test]
fn round_trips_request_and_shutdown() {
    let messages = [
        ClientMessage::Request {
            id: "r1".into(),
            session: "work".into(),
            method: "session.snapshot".into(),
            params: json!({}),
        },
        ClientMessage::Shutdown { id: "bye".into() },
    ];
    for message in messages {
        assert_eq!(decode(&message), message);
    }
}

#[test]
fn accepts_a_utf8_bom_from_a_windows_command_wrapper() {
    let input = b"\xef\xbb\xbf{\"type\":\"shutdown\",\"id\":\"bye\"}\n";
    assert_eq!(
        read_client_message(&mut BufReader::new(Cursor::new(input))).unwrap(),
        ClientMessage::Shutdown { id: "bye".into() },
    );
}

#[test]
fn reads_one_newline_delimited_message_at_a_time() {
    let mut input = BufReader::new(Cursor::new(
        b"{\"type\":\"shutdown\",\"id\":\"one\"}\n{\"type\":\"shutdown\",\"id\":\"two\"}\n",
    ));
    assert_eq!(
        read_client_message(&mut input).unwrap(),
        ClientMessage::Shutdown { id: "one".into() }
    );
    assert_eq!(
        read_client_message(&mut input).unwrap(),
        ClientMessage::Shutdown { id: "two".into() }
    );
}

#[test]
fn requires_a_newline_terminated_json_object() {
    let error = read_client_message(&mut BufReader::new(Cursor::new(
        br#"{"type":"shutdown","id":"bye"}"#,
    )))
    .unwrap_err();
    assert_eq!(error.code(), "unexpected_eof");

    let error = read_client_message(&mut BufReader::new(Cursor::new(b"[]\n"))).unwrap_err();
    assert_eq!(error.code(), "invalid_json");
}

#[test]
fn distinguishes_invalid_json_and_unsupported_protocol() {
    let error = read_client_message(&mut BufReader::new(Cursor::new(b"nope\n"))).unwrap_err();
    assert_eq!(error.code(), "invalid_json");

    let error = read_client_message(&mut BufReader::new(Cursor::new(
        b"{\"protocol\":2,\"type\":\"shutdown\",\"id\":\"bye\"}\n",
    )))
    .unwrap_err();
    assert_eq!(error.code(), "unsupported_protocol");
}

#[test]
fn writes_one_compact_flushed_line() {
    let message = ServerMessage::Sessions {
        sessions: vec![SessionDescriptor {
            name: "work".into(),
            running: true,
            socket_path: "/tmp/herdr.sock".into(),
        }],
    };
    let mut output = FlushTrackingWriter::default();
    write_server_message(&mut output, &message).unwrap();
    assert!(output.flushed);
    assert_eq!(
        String::from_utf8(output.bytes).unwrap(),
        "{\"type\":\"sessions\",\"sessions\":[{\"name\":\"work\",\"running\":true,\"socket_path\":\"/tmp/herdr.sock\"}]}\n"
    );
}

#[test]
fn version_prints_the_package_version() {
    let output = Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"))
        .arg("--version")
        .output()
        .unwrap();
    assert!(output.status.success());
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        concat!(env!("CARGO_PKG_VERSION"), "\n")
    );
}

#[test]
fn stdio_emits_hello_and_closes_on_shutdown() {
    let bridge = env!("CARGO_BIN_EXE_herdroid-bridge");
    let herdr = fake_herdr("0.8.0");
    let mut child = Command::new(bridge)
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .as_mut()
        .unwrap()
        .write_all(b"{\"type\":\"shutdown\",\"id\":\"bye\"}\n")
        .unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());

    let lines: Vec<ServerMessage> = String::from_utf8(output.stdout)
        .unwrap()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect();
    assert_eq!(lines.len(), 3);
    assert!(matches!(
        &lines[0],
        ServerMessage::Hello {
            protocol: PROTOCOL_VERSION,
            herdr_version,
            ..
        } if herdr_version == "0.8.0"
    ));
    assert_eq!(
        lines[1],
        ServerMessage::Sessions {
            sessions: Vec::new()
        }
    );
    assert_eq!(lines[2], ServerMessage::Closed { id: "bye".into() });
    fs::remove_file(herdr).unwrap();
}

#[test]
fn stdio_rejects_invalid_arguments() {
    let bridge = env!("CARGO_BIN_EXE_herdroid-bridge");
    assert_eq!(Command::new(bridge).status().unwrap().code(), Some(2));
    assert_eq!(
        Command::new(bridge)
            .args(["--stdio", "--herdr-bin", "relative-herdr"])
            .status()
            .unwrap()
            .code(),
        Some(2)
    );
    let missing = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("missing-herdr.exe");
    assert_eq!(
        Command::new(bridge)
            .args(["--stdio", "--herdr-bin", missing.to_str().unwrap()])
            .status()
            .unwrap()
            .code(),
        Some(2)
    );
}

#[test]
fn stdio_exits_cleanly_on_eof_and_reports_protocol_failures() {
    let bridge = env!("CARGO_BIN_EXE_herdroid-bridge");
    let herdr = fake_herdr("0.8.0");
    assert_eq!(stdio_status(&herdr), Some(0));

    let mut child = Command::new(bridge)
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .as_mut()
        .unwrap()
        .write_all(b"not-json\n")
        .unwrap();
    assert_eq!(child.wait().unwrap().code(), Some(3));
    fs::remove_file(herdr).unwrap();
}

#[test]
fn stdio_accepts_requests_after_runtime_support_exists() {
    let bridge = env!("CARGO_BIN_EXE_herdroid-bridge");
    let herdr = fake_herdr("0.8.0");
    let mut child = Command::new(bridge)
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .as_mut()
        .unwrap()
        .write_all(b"{\"type\":\"request\",\"id\":\"r1\",\"session\":\"work\",\"method\":\"session.snapshot\",\"params\":{}}\n")
        .unwrap();
    drop(child.stdin.take());
    let output = child.wait_with_output().unwrap();
    assert_eq!(output.status.code(), Some(0));
    assert!(String::from_utf8(output.stdout).unwrap().lines().any(|line| {
        serde_json::from_str::<ServerMessage>(line).is_ok_and(|message| matches!(
            message,
            ServerMessage::Error { id: Some(id), code, .. } if id == "r1" && code == "invalid_session"
        ))
    }));
    fs::remove_file(herdr).unwrap();
}

#[test]
fn stdio_enforces_the_minimum_herdr_version() {
    let older = fake_herdr("0.7.9");
    assert_eq!(stdio_status(&older), Some(3));
    fs::remove_file(older).unwrap();

    let prerelease = fake_herdr("0.8.0-preview.2026-08-04-d78e3d3b5126");
    assert_eq!(stdio_status(&prerelease), Some(0));
    fs::remove_file(prerelease).unwrap();

    let minimum = fake_herdr("0.8.0");
    assert_eq!(stdio_status(&minimum), Some(0));
    fs::remove_file(minimum).unwrap();
}

#[test]
fn stdio_exits_when_stdout_is_broken() {
    let herdr = fake_herdr("0.8.0");
    let mut child = Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"))
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    drop(child.stdout.take());
    assert_eq!(child.wait().unwrap().code(), Some(3));
    fs::remove_file(herdr).unwrap();
}

#[test]
fn stdio_exits_when_stdout_breaks_after_hello() {
    let herdr = fake_herdr("0.8.0");
    let mut child = Command::new(env!("CARGO_BIN_EXE_herdroid-bridge"))
        .args(["--stdio", "--herdr-bin", herdr.to_str().unwrap()])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut hello = String::new();
    BufReader::new(child.stdout.take().unwrap())
        .read_line(&mut hello)
        .unwrap();
    assert!(hello.contains("\"type\":\"hello\""));
    assert_eq!(child.wait().unwrap().code(), Some(3));
    fs::remove_file(herdr).unwrap();
}
