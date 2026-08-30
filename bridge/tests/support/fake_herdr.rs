use std::{
    fs,
    io::{BufRead, BufReader, Write},
    path::PathBuf,
    process::{self, Command},
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use interprocess::local_socket::{ListenerOptions, traits::Listener as _};
use serde_json::Value;

use crate::ipc::ApiEndpoint;

pub struct FakeApi {
    endpoint: ApiEndpoint,
    accepted: Arc<AtomicUsize>,
    worker: Option<JoinHandle<()>>,
    #[cfg(unix)]
    socket_path: PathBuf,
}

pub struct WedgeApi {
    endpoint: ApiEndpoint,
    stop: Arc<std::sync::atomic::AtomicBool>,
    worker: Option<JoinHandle<()>>,
    #[cfg(unix)]
    socket_path: PathBuf,
}

impl WedgeApi {
    pub fn new() -> Self {
        let socket_path = std::env::temp_dir().join(unique_name("herdroid-wedge-test", ".sock"));
        let listener = bind(&socket_path);
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stopped = Arc::clone(&stop);
        let worker = thread::spawn(move || {
            let _stream = listener.accept().expect("accept wedged API connection");
            while !stopped.load(Ordering::Acquire) {
                thread::sleep(std::time::Duration::from_millis(5));
            }
        });
        Self {
            endpoint: ApiEndpoint::from_reported_path(&socket_path).unwrap(),
            stop,
            worker: Some(worker),
            #[cfg(unix)]
            socket_path,
        }
    }

    pub fn endpoint(&self) -> &ApiEndpoint {
        &self.endpoint
    }
}

impl Drop for WedgeApi {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            worker.join().unwrap();
        }
        #[cfg(unix)]
        let _ = fs::remove_file(&self.socket_path);
    }
}

pub struct FakeHerdrBinary {
    pub path: PathBuf,
    sessions: Vec<FakeRuntimeSession>,
    state_path: PathBuf,
    mode_path: PathBuf,
    timeout_pid_path: PathBuf,
}

impl FakeHerdrBinary {
    pub fn sessions(names: impl IntoIterator<Item = impl AsRef<str>>) -> Self {
        Self::sessions_with_snapshot_marker(names, "")
    }

    pub fn sessions_with_snapshot_marker(
        names: impl IntoIterator<Item = impl AsRef<str>>,
        snapshot_marker: &str,
    ) -> Self {
        let sessions = names
            .into_iter()
            .map(|name| FakeRuntimeSession::new(name.as_ref(), snapshot_marker))
            .collect::<Vec<_>>();
        let listed = sessions
            .iter()
            .map(|session| {
                serde_json::json!({
                    "name": session.name,
                    "running": true,
                    "socket_path": session.socket_path,
                })
            })
            .collect::<Vec<_>>();
        let path = std::env::temp_dir().join(unique_name(
            "herdroid-runtime-test",
            if cfg!(windows) { ".cmd" } else { ".sh" },
        ));
        let listed_sessions = serde_json::json!({"sessions": listed});
        let state_path = path.with_extension("sessions.json");
        let mode_path = path.with_extension("mode");
        let timeout_pid_path = path.with_extension("timeout.pid");
        fs::write(&state_path, listed_sessions.to_string()).unwrap();
        fs::write(&mode_path, "normal").unwrap();
        #[cfg(windows)]
        fs::write(
            &path,
            format!(
                "@echo off\r\nif defined HERDROID_CHILD_SECRET echo %HERDROID_CHILD_SECRET% 1>&2\r\nif \"%1\"==\"--version\" echo herdr 0.8.0\r\nset /p HERDROID_MODE=<\"{}\"\r\nif \"%1\"==\"session\" if \"%HERDROID_MODE%\"==\"timeout\" powershell -NoProfile -Command \"$p=(Get-CimInstance Win32_Process -Filter ('ProcessId=' + $PID)).ParentProcessId; [IO.File]::WriteAllText('{}', [string]$p); Wait-Process -Id $p -ErrorAction SilentlyContinue\"\r\nif \"%1\"==\"session\" type \"{}\"\r\n", mode_path.display(), timeout_pid_path.display(), state_path.display()
            ),
        )
        .unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::write(
                &path,
                format!("#!/bin/sh\n[ -n \"$HERDROID_CHILD_SECRET\" ] && printf '%s\\n' \"$HERDROID_CHILD_SECRET\" >&2\nif [ \"$1\" = \"--version\" ]; then echo 'herdr 0.8.0'; else if [ \"$(cat '{}')\" = timeout ]; then printf '%s' \"$$\" > '{}'; exec sleep 60; fi; cat '{}'; fi\n", mode_path.display(), timeout_pid_path.display(), state_path.display()),
            )
            .unwrap();
            fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        }
        Self {
            path,
            sessions,
            state_path,
            mode_path,
            timeout_pid_path,
        }
    }

    pub fn set_running(&self, names: impl IntoIterator<Item = impl AsRef<str>>) {
        let names = names
            .into_iter()
            .map(|name| name.as_ref().to_owned())
            .collect::<Vec<_>>();
        let sessions = self.sessions.iter().filter(|session| names.contains(&session.name)).map(|session| serde_json::json!({"name":session.name,"running":true,"socket_path":session.socket_path})).collect::<Vec<_>>();
        fs::write(
            &self.state_path,
            serde_json::json!({"sessions":sessions}).to_string(),
        )
        .unwrap();
    }

    pub fn set_list_raw(&self, value: &str) {
        fs::write(&self.state_path, value).unwrap();
    }
    pub fn set_list_timeout(&self, timeout: bool) {
        if timeout {
            let _ = fs::remove_file(&self.timeout_pid_path);
        }
        fs::write(&self.mode_path, if timeout { "timeout" } else { "normal" }).unwrap();
        if !timeout {
            let _ = fs::remove_file(&self.timeout_pid_path);
        }
    }

    pub fn wait_for_timeout_pid(&self) -> u32 {
        let deadline = Instant::now() + Duration::from_secs(12);
        loop {
            if let Ok(value) = fs::read_to_string(&self.timeout_pid_path)
                && let Ok(pid) = value.trim().parse()
            {
                return pid;
            }
            assert!(
                Instant::now() < deadline,
                "timeout child did not publish its PID"
            );
            thread::sleep(Duration::from_millis(20));
        }
    }
}

impl Drop for FakeHerdrBinary {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
        let _ = fs::remove_file(&self.state_path);
        let _ = fs::remove_file(&self.mode_path);
        if let Ok(value) = fs::read_to_string(&self.timeout_pid_path)
            && let Ok(pid) = value.trim().parse::<u32>()
        {
            terminate_process(pid);
        }
        let _ = fs::remove_file(&self.timeout_pid_path);
    }
}

#[cfg(unix)]
fn terminate_process(pid: u32) {
    let _ = Command::new("kill")
        .args(["-KILL", &pid.to_string()])
        .status();
}

#[cfg(windows)]
fn terminate_process(pid: u32) {
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .status();
}

struct FakeRuntimeSession {
    name: String,
    socket_path: String,
    stop: Arc<std::sync::atomic::AtomicBool>,
    worker: Option<JoinHandle<()>>,
    #[cfg(unix)]
    socket_file: PathBuf,
}

impl FakeRuntimeSession {
    fn new(name: &str, snapshot_marker: &str) -> Self {
        let socket_name = unique_name(&format!("herdroid-runtime-{name}"), ".sock");
        #[cfg(unix)]
        let socket_file = std::env::temp_dir().join(&socket_name);
        #[cfg(windows)]
        let socket_file = PathBuf::from(socket_name);
        let listener = bind(&socket_file);
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stopped = Arc::clone(&stop);
        let snapshot_marker = snapshot_marker.to_owned();
        let worker = thread::spawn(move || {
            while !stopped.load(Ordering::Acquire) {
                let Ok(mut stream) = listener.accept() else {
                    return;
                };
                if stopped.load(Ordering::Acquire) {
                    return;
                }
                let stopped = Arc::clone(&stopped);
                let snapshot_marker = snapshot_marker.clone();
                thread::spawn(move || {
                    let mut line = Vec::new();
                    if BufReader::new(&mut stream)
                        .read_until(b'\n', &mut line)
                        .is_err()
                    {
                        return;
                    }
                    let Ok(request) = serde_json::from_slice::<Value>(&line) else {
                        return;
                    };
                    let id = request["id"].clone();
                    let response = match request["method"].as_str() {
                        Some("events.subscribe") => {
                            serde_json::json!({"id":id,"result":{"type":"subscription_started"}})
                        }
                        Some("session.snapshot") => {
                            serde_json::json!({"id":id,"result":{"type":"session_snapshot","snapshot":{"workspaces":[],"tabs":[],"panes":[],"layouts":[],"agents":[],"terminal_bytes":snapshot_marker}}})
                        }
                        Some("pane.close") => {
                            serde_json::json!({"id":id,"error":{"code":"pane_not_found","message":"pane pane_1 not found"}})
                        }
                        _ => serde_json::json!({"id":id,"result":{"ok":true}}),
                    };
                    if serde_json::to_writer(&mut stream, &response).is_err()
                        || stream.write_all(b"\n").is_err()
                        || stream.flush().is_err()
                    {
                        return;
                    }
                    while !stopped.load(Ordering::Acquire) {
                        thread::sleep(std::time::Duration::from_millis(10));
                    }
                });
            }
        });
        Self {
            name: name.into(),
            socket_path: socket_file.to_string_lossy().into_owned(),
            stop,
            worker: Some(worker),
            #[cfg(unix)]
            socket_file,
        }
    }
}

impl Drop for FakeRuntimeSession {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        let _ = wake(&self.socket_path);
        if let Some(worker) = self.worker.take() {
            worker.join().unwrap();
        }
        #[cfg(unix)]
        let _ = fs::remove_file(&self.socket_file);
    }
}

#[cfg(unix)]
fn wake(path: &str) -> std::io::Result<()> {
    use interprocess::local_socket::{GenericFilePath, Stream, prelude::*};
    let _ = Stream::connect(std::path::Path::new(path).to_fs_name::<GenericFilePath>()?);
    Ok(())
}

#[cfg(windows)]
fn wake(path: &str) -> std::io::Result<()> {
    use interprocess::local_socket::{GenericNamespaced, Stream, prelude::*};
    let _ = Stream::connect(path.to_ns_name::<GenericNamespaced>()?);
    Ok(())
}

impl FakeApi {
    pub fn reply(reply: Value) -> Self {
        Self::replies([reply])
    }

    pub fn replies(replies: impl IntoIterator<Item = Value>) -> Self {
        let socket_path = std::env::temp_dir().join(unique_name("herdroid-ipc-test", ".sock"));
        let listener = bind(&socket_path);
        let accepted = Arc::new(AtomicUsize::new(0));
        let accepted_by_worker = Arc::clone(&accepted);
        let replies: Vec<_> = replies.into_iter().collect();
        let worker = thread::spawn(move || {
            let mut stream = listener.accept().expect("accept fake API connection");
            accepted_by_worker.fetch_add(1, Ordering::Relaxed);
            let mut line = Vec::new();
            BufReader::new(&mut stream)
                .read_until(b'\n', &mut line)
                .expect("read fake API request");
            serde_json::from_slice::<Value>(&line).expect("request is JSON");
            for reply in replies {
                serde_json::to_writer(&mut stream, &reply).expect("write fake API response");
                stream
                    .write_all(b"\n")
                    .expect("terminate fake API response");
                stream.flush().expect("flush fake API response");
            }
        });

        Self {
            endpoint: ApiEndpoint::from_reported_path(&socket_path).expect("make endpoint"),
            accepted,
            worker: Some(worker),
            #[cfg(unix)]
            socket_path,
        }
    }

    pub fn endpoint(&self) -> &ApiEndpoint {
        &self.endpoint
    }

    pub fn accepted_connections(&self) -> usize {
        self.accepted.load(Ordering::Relaxed)
    }
}

impl Drop for FakeApi {
    fn drop(&mut self) {
        if let Some(worker) = self.worker.take() {
            worker.join().expect("fake API worker did not panic");
        }
        #[cfg(unix)]
        let _ = std::fs::remove_file(&self.socket_path);
    }
}

fn unique_name(prefix: &str, suffix: &str) -> String {
    static NEXT_NAME: AtomicUsize = AtomicUsize::new(0);
    format!(
        "{prefix}-{}-{}{suffix}",
        process::id(),
        NEXT_NAME.fetch_add(1, Ordering::Relaxed),
    )
}

#[cfg(unix)]
fn bind(path: &std::path::Path) -> interprocess::local_socket::Listener {
    use interprocess::local_socket::{GenericFilePath, prelude::*};

    ListenerOptions::new()
        .name(
            path.to_fs_name::<GenericFilePath>()
                .expect("make file socket name"),
        )
        .create_sync()
        .expect("bind fake API socket")
}

#[cfg(windows)]
fn bind(path: &std::path::Path) -> interprocess::local_socket::Listener {
    use interprocess::local_socket::{GenericNamespaced, prelude::*};

    ListenerOptions::new()
        .name(
            path.to_string_lossy()
                .to_ns_name::<GenericNamespaced>()
                .expect("make namespaced pipe name"),
        )
        .create_sync()
        .expect("bind fake API pipe")
}
