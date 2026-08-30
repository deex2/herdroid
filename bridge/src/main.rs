#[allow(dead_code)]
mod herdr;
#[allow(dead_code)]
mod ipc;
#[allow(dead_code)]
mod monitor;
mod protocol;
mod runtime;

use std::{
    env, io,
    path::Path,
    process,
    time::{SystemTime, UNIX_EPOCH},
};

use protocol::{PROTOCOL_VERSION, ServerMessage, write_server_message};

const MIN_HERDR_VERSION: &str = "0.8.0";

fn main() {
    process::exit(match run(env::args().skip(1).collect()) {
        Ok(()) => 0,
        Err(2) => 2,
        Err(_) => 3,
    });
}

fn run(arguments: Vec<String>) -> Result<(), i32> {
    if arguments == ["--version"] {
        println!("{}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }

    let [stdio, herdr_flag, herdr_bin] = arguments.as_slice() else {
        return Err(2);
    };
    let herdr_path = Path::new(herdr_bin);
    if stdio != "--stdio"
        || herdr_flag != "--herdr-bin"
        || !herdr_path.is_absolute()
        || !herdr_path.is_file()
    {
        return Err(2);
    }

    let herdr_version = herdr_version(herdr_bin).map_err(|_| 3)?;
    let mut output = io::stdout().lock();
    let epoch = format!(
        "{}-{}",
        process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| 3)?
            .as_nanos()
    );
    write_server_message(
        &mut output,
        &ServerMessage::Hello {
            protocol: PROTOCOL_VERSION,
            epoch: epoch.clone(),
            bridge_version: env!("CARGO_PKG_VERSION").into(),
            target_os: env::consts::OS.into(),
            target_arch: env::consts::ARCH.into(),
            herdr_version,
        },
    )
    .map_err(|_| 3)?;

    runtime::run(herdr_bin, epoch, &mut output).map_err(|_| 3)
}

fn herdr_version(path: &str) -> io::Result<String> {
    let output = runtime::child_stdout(path, ["--version"])?;
    let output = String::from_utf8(output)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid herdr --version UTF-8"))?;
    parse_herdr_version(&output)
}

fn parse_herdr_version(output: &str) -> io::Result<String> {
    let version = output
        .trim()
        .strip_prefix("herdr ")
        .ok_or_else(|| io::Error::other("invalid herdr --version output"))?;
    let (core_and_prerelease, _) = version
        .split_once('+')
        .map_or((version, None), |parts| (parts.0, Some(parts.1)));
    let (core, prerelease) = core_and_prerelease
        .split_once('-')
        .map_or((core_and_prerelease, None), |parts| {
            (parts.0, Some(parts.1))
        });
    let mut parts = core.split('.');
    let parse_part = |part: &str| {
        part.parse::<u64>()
            .map_err(|_| io::Error::other("invalid herdr version"))
    };
    let major = parts
        .next()
        .ok_or_else(|| io::Error::other("invalid herdr version"))
        .and_then(parse_part)?;
    let minor = parts
        .next()
        .ok_or_else(|| io::Error::other("invalid herdr version"))
        .and_then(parse_part)?;
    let patch = parts
        .next()
        .ok_or_else(|| io::Error::other("invalid herdr version"))
        .and_then(parse_part)?;
    if parts.next().is_some()
        || (major, minor, patch) < (0, 8, 0)
        || prerelease.is_some_and(|value| {
            value.split('.').any(|part| {
                part.is_empty()
                    || !part
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
            })
        })
    {
        return Err(io::Error::other(format!(
            "Herdroid requires Herdr {MIN_HERDR_VERSION} or newer"
        )));
    }
    Ok(version.to_owned())
}
