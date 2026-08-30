use std::{
    fs,
    path::Path,
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};

fn repository_root() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("bridge has a repository root")
        .to_path_buf()
}

fn package(root: &Path, target: &str, binary: &Path, output: &Path) {
    let status = Command::new("pwsh")
        .args([
            "-NoProfile",
            "-File",
            root.join("scripts/package-bridge.ps1").to_str().unwrap(),
            "-Target",
            target,
            "-Binary",
            binary.to_str().unwrap(),
            "-Output",
            output.to_str().unwrap(),
        ])
        .status()
        .unwrap();
    assert!(status.success());
}

#[test]
fn plugin_manifest_is_manifest_only_and_requires_herdr_0_8() {
    let text = fs::read_to_string(repository_root().join("plugin/herdr-plugin.toml")).unwrap();

    assert!(text.contains("id = \"dev.herdroid.bridge\""));
    assert!(text.contains("min_herdr_version = \"0.8.0\""));
    assert!(text.contains(&format!("version = \"{}\"", env!("CARGO_PKG_VERSION"))));
    assert!(!text.contains("[[startup]]"));
    assert!(!text.contains("[[events]]"));
    assert!(!text.contains("[[actions]]"));
}

#[test]
fn bridge_dependency_versions_are_pinned_to_the_approved_graph() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let manifest = fs::read_to_string(root.join("Cargo.toml")).unwrap();
    let lock = fs::read_to_string(root.join("Cargo.lock"))
        .unwrap()
        .replace("\r\n", "\n");
    assert!(manifest.contains("interprocess = \"=2.4.3\""));
    for expected in [
        "name = \"interprocess\"\nversion = \"2.4.3\"",
        "name = \"serde\"\nversion = \"1.0.229\"",
        "name = \"serde_json\"\nversion = \"1.0.151\"",
        "name = \"windows-sys\"\nversion = \"0.61.2\"",
    ] {
        assert!(lock.contains(expected), "missing {expected}");
    }
}

#[test]
fn packaging_upserts_three_targets_and_replaces_one_with_stable_bytes() {
    let root = repository_root();
    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let temp = std::env::temp_dir().join(format!("herdroid-package-{unique}"));
    let output = temp.join("out");
    fs::create_dir_all(&temp).unwrap();
    let windows_binary = temp.join("bridge.exe");
    let linux_binary = temp.join("bridge");
    let arm_binary = temp.join("bridge-arm");
    fs::write(&windows_binary, b"windows bridge v1").unwrap();
    fs::write(&linux_binary, b"linux bridge").unwrap();
    fs::write(&arm_binary, b"arm bridge").unwrap();

    package(&root, "x86_64-pc-windows-msvc", &windows_binary, &output);
    package(&root, "x86_64-unknown-linux-gnu", &linux_binary, &output);
    package(&root, "aarch64-unknown-linux-gnu", &arm_binary, &output);
    fs::write(&windows_binary, b"windows bridge v2").unwrap();
    package(&root, "x86_64-pc-windows-msvc", &windows_binary, &output);

    let catalog = fs::read(output.join("catalog.json")).unwrap();
    assert!(!catalog.contains(&b'\r'), "catalog must use LF only");
    let parsed: serde_json::Value = serde_json::from_slice(&catalog).unwrap();
    assert_eq!(parsed["plugin_id"].as_str(), Some("dev.herdroid.bridge"));
    assert_eq!(parsed["plugin_version"].as_str(), Some("0.1.0"));
    assert_eq!(parsed["min_herdr_version"].as_str(), Some("0.8.0"));
    assert_eq!(parsed["protocol"].as_u64(), Some(1));
    let targets = parsed["targets"].as_array().unwrap();
    assert_eq!(
        targets
            .iter()
            .map(|entry| entry["target"].as_str().unwrap())
            .collect::<Vec<_>>(),
        [
            "aarch64-unknown-linux-gnu",
            "x86_64-pc-windows-msvc",
            "x86_64-unknown-linux-gnu",
        ]
    );
    assert_eq!(
        targets
            .iter()
            .map(|entry| entry["sha256"].as_str().unwrap())
            .collect::<Vec<_>>(),
        [
            "de44107cdce1879bda8ec375a2bf6ecf264c81ed363110c4ba49de3b819faab2",
            "06d393c48592c574c23497b0691ceb75a4505d5476a89f1b10be19cbcbcdc6e9",
            "57c0c0ee4ff02d8dd8414dd3684d62da37be9b67ffa66ffc83f1f73e69dd7394",
        ]
    );
    assert!(targets.iter().all(|entry| {
        ["binary", "manifest"]
            .into_iter()
            .all(|field| output.join(entry[field].as_str().unwrap()).is_file())
    }));
    package(&root, "x86_64-pc-windows-msvc", &windows_binary, &output);
    assert_eq!(catalog, fs::read(output.join("catalog.json")).unwrap());
    assert_eq!(
        fs::read(output.join("x86_64-pc-windows-msvc/bin/herdroid-bridge.exe")).unwrap(),
        b"windows bridge v2"
    );
    fs::remove_dir_all(temp).unwrap();
}
