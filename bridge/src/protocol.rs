use std::{
    fmt,
    io::{self, BufRead, Read, Write},
};

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_LINE_BYTES: usize = 1_048_576;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SessionDescriptor {
    pub name: String,
    pub running: bool,
    pub socket_path: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    Request {
        id: String,
        session: String,
        method: String,
        params: Value,
    },
    Shutdown {
        id: String,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    Hello {
        protocol: u32,
        epoch: String,
        bridge_version: String,
        target_os: String,
        target_arch: String,
        herdr_version: String,
    },
    Snapshot {
        session: String,
        epoch: String,
        baseline: bool,
        snapshot: Value,
    },
    AgentStatus {
        session: String,
        epoch: String,
        pane_id: String,
        status: String,
    },
    Degraded {
        session: String,
        epoch: String,
        code: String,
        message: String,
        uncovered_pane_ids: Vec<String>,
    },
    Response {
        id: String,
        session: String,
        result: Value,
    },
    Sessions {
        sessions: Vec<SessionDescriptor>,
    },
    Heartbeat {
        epoch: String,
    },
    Error {
        id: Option<String>,
        session: Option<String>,
        code: String,
        message: String,
    },
    Closed {
        id: String,
    },
}

#[derive(Debug)]
pub struct ProtocolError {
    code: &'static str,
}

impl ProtocolError {
    pub fn code(&self) -> &'static str {
        self.code
    }
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.code)
    }
}

impl std::error::Error for ProtocolError {}

fn error(code: &'static str) -> ProtocolError {
    ProtocolError { code }
}

pub fn read_client_message(reader: &mut impl BufRead) -> Result<ClientMessage, ProtocolError> {
    let mut line = Vec::new();
    reader
        .by_ref()
        .take((MAX_LINE_BYTES + 1) as u64)
        .read_until(b'\n', &mut line)
        .map_err(|_| error("unexpected_eof"))?;

    if line.is_empty() {
        return Err(error("unexpected_eof"));
    }
    if line.len() == MAX_LINE_BYTES + 1 && line.last() != Some(&b'\n') {
        return Err(error("line_too_large"));
    }
    if line.last() != Some(&b'\n') {
        return Err(error("unexpected_eof"));
    }

    let json = line.strip_prefix(b"\xef\xbb\xbf").unwrap_or(&line);
    let value: Value = serde_json::from_slice(json).map_err(|_| error("invalid_json"))?;
    let object = value.as_object().ok_or_else(|| error("invalid_json"))?;
    if object
        .get("protocol")
        .is_some_and(|version| version.as_u64() != Some(PROTOCOL_VERSION as u64))
    {
        return Err(error("unsupported_protocol"));
    }
    serde_json::from_value(value).map_err(|_| error("invalid_json"))
}

pub fn write_server_message(writer: &mut impl Write, message: &ServerMessage) -> io::Result<()> {
    serde_json::to_writer(&mut *writer, message)?;
    writer.write_all(b"\n")?;
    writer.flush()
}
