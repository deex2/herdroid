use std::{
    io,
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::Instant,
};

pub type LocalSocketStream = interprocess::local_socket::Stream;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Platform {
    Unix,
    Windows,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ApiEndpoint {
    UnixFile(PathBuf),
    WindowsNamespaced(PathBuf),
}

impl ApiEndpoint {
    pub fn from_reported_path(path: impl AsRef<Path>) -> io::Result<Self> {
        Self::for_platform(
            if cfg!(windows) {
                Platform::Windows
            } else {
                Platform::Unix
            },
            path,
        )
    }

    pub fn for_platform(platform: Platform, path: impl AsRef<Path>) -> io::Result<Self> {
        let path = path.as_ref();
        if path.as_os_str().is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "herdr_socket_path_empty",
            ));
        }
        Ok(match platform {
            Platform::Unix => Self::UnixFile(path.to_owned()),
            Platform::Windows => Self::WindowsNamespaced(path.to_owned()),
        })
    }

    pub(crate) fn connect_until(
        &self,
        cancelled: &AtomicBool,
        deadline: Instant,
    ) -> io::Result<LocalSocketStream> {
        loop {
            if cancelled.load(Ordering::Acquire) {
                return Err(io::Error::new(
                    io::ErrorKind::Interrupted,
                    "herdr_api_cancelled",
                ));
            }
            let timeout = deadline
                .saturating_duration_since(Instant::now())
                .min(std::time::Duration::from_millis(10));
            if timeout.is_zero() {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "herdr_api_timeout"));
            }
            let result = match self {
                #[cfg(unix)]
                Self::UnixFile(path) => connect_reported_until(path, timeout),
                #[cfg(windows)]
                Self::WindowsNamespaced(path) => connect_reported_until(path, timeout),
                _ => Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "herdr_endpoint_platform_mismatch",
                )),
            };
            match result {
                Ok(stream) => return Ok(stream),
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) => {}
                Err(error) => return Err(error),
            }
        }
    }
}

#[cfg(unix)]
fn connect_reported_until(
    path: &Path,
    timeout: std::time::Duration,
) -> io::Result<LocalSocketStream> {
    use interprocess::{
        ConnectWaitMode,
        local_socket::{ConnectOptions, GenericFilePath, prelude::*},
    };
    ConnectOptions::new()
        .name(path.to_fs_name::<GenericFilePath>()?)
        .wait_mode(ConnectWaitMode::Timeout(timeout))
        .nonblocking_stream(true)
        .connect_sync()
}

#[cfg(windows)]
fn connect_reported_until(
    path: &Path,
    timeout: std::time::Duration,
) -> io::Result<LocalSocketStream> {
    use interprocess::{
        ConnectWaitMode,
        local_socket::{ConnectOptions, GenericNamespaced, prelude::*},
    };
    let identity = path.to_string_lossy().to_string();
    ConnectOptions::new()
        .name(identity.to_ns_name::<GenericNamespaced>()?)
        .wait_mode(ConnectWaitMode::Timeout(timeout))
        .nonblocking_stream(true)
        .connect_sync()
}
