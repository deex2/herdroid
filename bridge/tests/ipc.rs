mod support;

#[path = "../src/herdr.rs"]
mod herdr;
#[path = "../src/ipc.rs"]
mod ipc;

use herdr::{api_call, api_call_until};
use ipc::{ApiEndpoint, Platform};
use serde_json::{Value, json};
use std::{
    sync::Arc,
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};
use support::fake_herdr::{FakeApi, WedgeApi};

#[test]
fn windows_endpoint_uses_reported_path_as_namespaced_identity() {
    let endpoint = ApiEndpoint::for_platform(
        Platform::Windows,
        r"C:\Users\dev\AppData\Local\herdr\sessions\work\herdr.sock",
    )
    .unwrap();

    assert!(matches!(endpoint, ApiEndpoint::WindowsNamespaced(_)));
}

#[test]
fn api_call_honors_cancellation_before_connect() {
    let cancelled = AtomicBool::new(true);
    let error = api_call_until(
        &ApiEndpoint::for_platform(Platform::Windows, "unused").unwrap(),
        &json!({"id":"r1","method":"ping","params":{}}),
        &cancelled,
        Instant::now() + Duration::from_secs(1),
    )
    .unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::Interrupted);
}

#[test]
fn api_call_honors_cancellation_while_waiting_for_reply() {
    assert_api_call_cancels(json!({"id":"r1","method":"ping","params":{}}));
}

#[test]
fn api_call_honors_cancellation_while_large_request_backpressures() {
    assert_api_call_cancels(
        json!({"id":"r1","method":"ping","params":{"data":"x".repeat(1_048_000)}}),
    );
}

fn assert_api_call_cancels(request: Value) {
    let fake = WedgeApi::new();
    let cancelled = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&cancelled);
    let endpoint = fake.endpoint().clone();
    let worker = thread::spawn(move || {
        api_call_until(
            &endpoint,
            &request,
            &signal,
            Instant::now() + Duration::from_secs(2),
        )
        .unwrap_err()
    });
    thread::sleep(Duration::from_millis(30));
    cancelled.store(true, Ordering::Release);
    assert_eq!(
        worker.join().unwrap().kind(),
        std::io::ErrorKind::Interrupted
    );
}

#[test]
fn api_call_opens_one_connection_and_reads_one_response() {
    let fake = FakeApi::reply(json!({"id":"r1","result":{"type":"pong"}}));

    let result = api_call(
        fake.endpoint(),
        &json!({"id":"r1","method":"ping","params":{}}),
    )
    .unwrap();

    assert_eq!(result["id"], "r1");
    assert_eq!(fake.accepted_connections(), 1);
}

#[test]
fn api_call_rejects_a_mismatched_response_id() {
    let fake = FakeApi::reply(json!({"id":"other","result":{"type":"pong"}}));

    let error = api_call(
        fake.endpoint(),
        &json!({"id":"r1","method":"ping","params":{}}),
    )
    .unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert_eq!(error.to_string(), "herdr_response_id_mismatch");
}

#[test]
fn api_call_stops_when_a_accepted_connection_never_replies() {
    let fake = WedgeApi::new();
    let cancelled = AtomicBool::new(false);
    let deadline = Instant::now() + Duration::from_millis(50);

    let error = api_call_until(
        fake.endpoint(),
        &json!({"id":"r1","method":"ping","params":{}}),
        &cancelled,
        deadline,
    )
    .unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
}

#[test]
fn subscription_rejects_an_api_error_response() {
    let fake = FakeApi::reply(json!({"id":"sub1","error":{"code":"invalid_request"}}));
    let request = json!({"id":"sub1","method":"events.subscribe","params":{"subscriptions":[]}});
    let mut subscription = herdr::SubscriptionReader::open(fake.endpoint(), &request).unwrap();

    let error = subscription.next(&AtomicBool::new(false)).unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert_eq!(error.to_string(), "herdr_api_error");
}

#[test]
fn subscription_retains_no_id_event_framing_then_rejects_a_mismatched_id() {
    let fake = FakeApi::replies([
        json!({"id":"sub1","result":{"type":"subscription_started"}}),
        json!({"event":"pane_agent_status_changed","data":{"pane_id":"p1","status":"working"}}),
        json!({"id":"wrong","result":{"type":"unexpected"}}),
    ]);
    let request = json!({"id":"sub1","method":"events.subscribe","params":{"subscriptions":[]}});
    let mut subscription = herdr::SubscriptionReader::open(fake.endpoint(), &request).unwrap();
    let cancelled = AtomicBool::new(false);

    assert_eq!(
        subscription.next(&cancelled).unwrap().unwrap()["result"]["type"],
        "subscription_started"
    );
    assert_eq!(
        subscription.next(&cancelled).unwrap().unwrap()["event"],
        "pane_agent_status_changed"
    );
    let error = subscription.next(&cancelled).unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert_eq!(error.to_string(), "herdr_response_id_mismatch");
    assert_eq!(fake.accepted_connections(), 1);
}

#[test]
fn subscription_open_honors_cancellation_before_connect() {
    let endpoint = ApiEndpoint::from_reported_path("cancelled-subscription.sock").unwrap();
    let cancelled = AtomicBool::new(true);
    let error = match herdr::SubscriptionReader::open_until(
        &endpoint,
        &json!({"id":"sub-wedged","method":"events.subscribe","params":{"subscriptions":[{"type":"pane.updated"}]}}),
        &cancelled,
        Instant::now() + Duration::from_secs(5),
    ) {
        Ok(_) => panic!("wedged subscription unexpectedly opened"),
        Err(error) => error,
    };
    assert_eq!(error.kind(), std::io::ErrorKind::Interrupted);
}
