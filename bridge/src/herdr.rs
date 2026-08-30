use std::{
    io::{self, Read, Write},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::Duration,
};

use serde_json::Value;

use crate::ipc::{ApiEndpoint, LocalSocketStream};

const MAX_API_LINE_BYTES: usize = 1_048_576;
const POLL_INTERVAL: Duration = Duration::from_millis(10);
pub const API_CALL_TIMEOUT: Duration = Duration::from_secs(5);

pub fn api_call(endpoint: &ApiEndpoint, request: &Value) -> io::Result<Value> {
    api_call_until(
        endpoint,
        request,
        &AtomicBool::new(false),
        std::time::Instant::now() + API_CALL_TIMEOUT,
    )
}

pub fn api_call_until(
    endpoint: &ApiEndpoint,
    request: &Value,
    cancelled: &AtomicBool,
    deadline: std::time::Instant,
) -> io::Result<Value> {
    let response = api_response_until(endpoint, request, cancelled, deadline)?;
    validate_response(&response, request_id(request)?)?;
    Ok(response)
}

pub fn api_response_until(
    endpoint: &ApiEndpoint,
    request: &Value,
    cancelled: &AtomicBool,
    deadline: std::time::Instant,
) -> io::Result<Value> {
    let request_id = request_id(request)?;
    let mut stream = endpoint.connect_until(cancelled, deadline)?;
    write_request_until(&mut stream, request, cancelled, deadline)?;
    let mut pending = Vec::new();
    loop {
        if cancelled.load(Ordering::Acquire) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "herdr_api_cancelled",
            ));
        }
        if std::time::Instant::now() >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "herdr_api_timeout"));
        }
        match poll_stream(&mut stream, &mut pending)? {
            Poll::Data => {
                if let Some(line) = take_line(&mut pending)? {
                    let response = decode_line(&line)?;
                    validate_response_id(&response, request_id)?;
                    return Ok(response);
                }
            }
            Poll::Pending => thread::sleep(POLL_INTERVAL),
            Poll::Closed => return Err(closed_pipe()),
        }
    }
}

pub struct SubscriptionReader {
    stream: LocalSocketStream,
    request_id: String,
    pending: Vec<u8>,
    started: bool,
}

impl SubscriptionReader {
    #[allow(dead_code)]
    pub fn open(endpoint: &ApiEndpoint, request: &Value) -> io::Result<Self> {
        Self::open_until(
            endpoint,
            request,
            &AtomicBool::new(false),
            std::time::Instant::now() + API_CALL_TIMEOUT,
        )
    }

    pub fn open_until(
        endpoint: &ApiEndpoint,
        request: &Value,
        cancelled: &AtomicBool,
        deadline: std::time::Instant,
    ) -> io::Result<Self> {
        if request.get("method").and_then(Value::as_str) != Some("events.subscribe") {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "herdr_subscription_method",
            ));
        }
        let request_id = request_id(request)?.to_owned();
        let mut stream = endpoint.connect_until(cancelled, deadline)?;
        write_request_until(&mut stream, request, cancelled, deadline)?;
        Ok(Self {
            stream,
            request_id,
            pending: Vec::new(),
            started: false,
        })
    }

    pub fn next(&mut self, cancelled: &AtomicBool) -> io::Result<Option<Value>> {
        loop {
            if cancelled.load(Ordering::Acquire) {
                return Ok(None);
            }
            if let Some(line) = self.take_line()? {
                let response = decode_line(&line)?;
                if !self.started {
                    validate_response(&response, &self.request_id)?;
                    if response
                        .get("result")
                        .and_then(Value::as_object)
                        .and_then(|result| result.get("type"))
                        .and_then(Value::as_str)
                        != Some("subscription_started")
                    {
                        return Err(invalid_data("herdr_subscription_not_started"));
                    }
                    self.started = true;
                } else if response.get("id").is_some() {
                    validate_response(&response, &self.request_id)?;
                } else if response.get("error").is_some() {
                    return Err(invalid_data("herdr_api_error"));
                }
                return Ok(Some(response));
            }

            match poll_stream(&mut self.stream, &mut self.pending)? {
                Poll::Data => {}
                Poll::Pending => thread::sleep(POLL_INTERVAL),
                Poll::Closed => return Ok(None),
            }
        }
    }

    fn take_line(&mut self) -> io::Result<Option<Vec<u8>>> {
        take_line(&mut self.pending)
    }
}

fn request_id(request: &Value) -> io::Result<&str> {
    request
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "herdr_request_id_missing"))
}

fn write_request_until(
    stream: &mut LocalSocketStream,
    request: &Value,
    cancelled: &AtomicBool,
    deadline: std::time::Instant,
) -> io::Result<()> {
    let mut bytes =
        serde_json::to_vec(request).map_err(|_| invalid_data("herdr_request_invalid"))?;
    if bytes.len() + 1 > MAX_API_LINE_BYTES {
        return Err(invalid_data("herdr_line_too_large"));
    }
    bytes.push(b'\n');
    let mut offset = 0;
    while offset < bytes.len() {
        if cancelled.load(Ordering::Acquire) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "herdr_api_cancelled",
            ));
        }
        if std::time::Instant::now() >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "herdr_api_timeout"));
        }
        match stream.write(&bytes[offset..]) {
            Ok(0) => thread::sleep(POLL_INTERVAL),
            Ok(written) => offset += written,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                ) =>
            {
                thread::sleep(POLL_INTERVAL)
            }
            Err(error) => return Err(error),
        }
    }
    stream.flush()
}

fn take_line(pending: &mut Vec<u8>) -> io::Result<Option<Vec<u8>>> {
    let Some(end) = pending.iter().position(|byte| *byte == b'\n') else {
        if pending.len() > MAX_API_LINE_BYTES {
            return Err(invalid_data("herdr_line_too_large"));
        }
        return Ok(None);
    };
    if end + 1 > MAX_API_LINE_BYTES {
        return Err(invalid_data("herdr_line_too_large"));
    }
    Ok(Some(pending.drain(..=end).collect()))
}

fn decode_line(line: &[u8]) -> io::Result<Value> {
    serde_json::from_slice(line).map_err(|_| invalid_data("herdr_invalid_json"))
}

fn validate_response(response: &Value, expected_id: &str) -> io::Result<()> {
    if response.get("error").is_some() {
        return Err(invalid_data("herdr_api_error"));
    }
    validate_response_id(response, expected_id)
}

fn validate_response_id(response: &Value, expected_id: &str) -> io::Result<()> {
    if response.get("id").and_then(Value::as_str) != Some(expected_id) {
        return Err(invalid_data("herdr_response_id_mismatch"));
    }
    Ok(())
}

fn closed_pipe() -> io::Error {
    io::Error::new(io::ErrorKind::UnexpectedEof, "herdr_closed_pipe")
}

fn invalid_data(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

enum Poll {
    Data,
    Pending,
    Closed,
}

#[cfg(unix)]
fn poll_stream(stream: &mut LocalSocketStream, pending: &mut Vec<u8>) -> io::Result<Poll> {
    let mut bytes = [0_u8; 4096];
    match stream.read(&mut bytes) {
        Ok(0) => Ok(Poll::Closed),
        Ok(read) => {
            pending.extend_from_slice(&bytes[..read]);
            Ok(Poll::Data)
        }
        Err(error)
            if matches!(
                error.kind(),
                io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
            ) =>
        {
            Ok(Poll::Pending)
        }
        Err(error) => Err(error),
    }
}

#[cfg(windows)]
fn poll_stream(stream: &mut LocalSocketStream, pending: &mut Vec<u8>) -> io::Result<Poll> {
    use std::os::windows::io::{AsHandle, AsRawHandle};

    let LocalSocketStream::NamedPipe(pipe) = stream;
    let mut available = 0;
    let ok = unsafe {
        windows_sys::Win32::System::Pipes::PeekNamedPipe(
            pipe.as_handle().as_raw_handle(),
            std::ptr::null_mut(),
            0,
            std::ptr::null_mut(),
            &mut available,
            std::ptr::null_mut(),
        )
    };
    if ok == 0 {
        let error = io::Error::last_os_error();
        return if matches!(error.raw_os_error(), Some(6 | 109 | 232 | 233)) {
            Ok(Poll::Closed)
        } else {
            Err(error)
        };
    }
    if available == 0 {
        return Ok(Poll::Pending);
    }

    let mut bytes = vec![0_u8; available.min(4096) as usize];
    match stream.read(&mut bytes) {
        Ok(0) => Ok(Poll::Closed),
        Ok(read) => {
            pending.extend_from_slice(&bytes[..read]);
            Ok(Poll::Data)
        }
        Err(error) if matches!(error.raw_os_error(), Some(6 | 109 | 232 | 233)) => Ok(Poll::Closed),
        Err(error) => Err(error),
    }
}
