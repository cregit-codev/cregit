// Smoke test: run the built binary on a fixture and check the stream is well-formed.

use std::process::Command;

fn run(fixture: &str) -> String {
    let bin = env!("CARGO_BIN_EXE_rust_tokenizer");
    let out = Command::new(bin)
        .arg(format!("tests/fixtures/{}", fixture))
        .output()
        .expect("failed to run rust_tokenizer");
    assert!(out.status.success(), "binary exited with {}", out.status);
    String::from_utf8(out.stdout).expect("stdout is not utf-8")
}

#[test]
fn hello_has_unit_markers_and_expected_tokens() {
    let s = run("hello.rs");
    let first = s.lines().next().unwrap();
    let last = s.lines().last().unwrap();
    assert!(first.starts_with("-:-\tbegin_unit|"), "got: {}", first);
    assert_eq!(last, "-:-\tend_unit");
    // values are emitted raw (no quoting), matching what prettyPrint expects
    assert!(s.contains("\tkeyword|fn"));
    assert!(s.contains("\tidentifier|main"));
    assert!(s.contains("\tliteral|\"hello, world\""));
}

#[test]
fn edge_cases_classify_correctly() {
    let s = run("edge_cases.rs");
    // lifetimes vs char literals
    assert!(s.contains("\tlifetime|'a"));
    assert!(s.contains("\tliteral|'x'"));
    // raw / byte literals — all unified under `literal|` for prettyPrint
    assert!(s.contains("\tliteral|r#\"raw \"quoted\" string\"#"));
    assert!(s.contains("\tliteral|b'y'"));
    assert!(s.contains("\tliteral|b\"bytes\""));
    assert!(s.contains("\tliteral|br#\"raw bytes\"#"));
    // raw and unicode identifiers
    assert!(s.contains("\tidentifier|r#type"));
    assert!(s.contains("\tidentifier|café"));
    // nested block comment kept as one token, raw text
    assert!(s.contains("\tcomment|/* outer /* inner */ outer */"));
    // numeric literals with suffixes/underscores
    assert!(s.contains("\tliteral|1_000_000u64"));
    assert!(s.contains("\tliteral|2.5f64"));
}

#[test]
fn unicode_identifier_advances_columns_by_code_point_not_byte() {
    // `café` is 4 chars / 5 bytes. The `(` immediately after must be at col 8 (1+4+space+4),
    // not col 9 (which we would get if we counted bytes).
    let s = run("edge_cases.rs");
    assert!(
        s.contains("19:8\top|("),
        "expected `(` at col 8 after café, output was:\n{}",
        s
    );
}
