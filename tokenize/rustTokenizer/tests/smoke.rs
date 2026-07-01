// Smoke test: run the built binary on a fixture and check the stream is well-formed.

use std::process::{Command, Output};

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_rust_tokenizer")
}

// Run the binary with arbitrary args and return the raw Output (for exit-code checks).
fn run_args(args: &[&str]) -> Output {
    Command::new(bin())
        .args(args)
        .output()
        .expect("failed to run rust_tokenizer")
}

fn run(fixture: &str) -> String {
    let out = run_args(&[&format!("tests/fixtures/{}", fixture)]);
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
    // `fn café()` is on line 18; `café` is 4 chars / 5 bytes. The `(` must be at col 8
    // (1 + "fn " + 4 chars), not col 9 — which is what counting bytes would give.
    let s = run("edge_cases.rs");
    assert!(
        s.contains("18:8\top|("),
        "expected `(` at col 8 after café, output was:\n{}",
        s
    );
}

// --- unhappy paths: the binary must report errors with the documented exit codes ---

#[test]
fn missing_input_file_exits_1() {
    let out = run_args(&["does/not/exist.rs"]);
    assert_eq!(out.status.code(), Some(1));
    let err = String::from_utf8_lossy(&out.stderr);
    assert!(err.contains("cannot read"), "stderr was: {}", err);
}

#[test]
fn unknown_option_exits_2() {
    let out = run_args(&["--bogus", "tests/fixtures/hello.rs"]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("unknown option"));
}

#[test]
fn extra_positional_argument_exits_2() {
    let out = run_args(&["tests/fixtures/hello.rs", "tests/fixtures/edge_cases.rs"]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("extra positional"));
}

#[test]
fn no_path_prints_usage_and_exits_2() {
    let out = run_args(&[]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("Usage"));
}

#[test]
fn dispatcher_flags_are_accepted_and_ignored() {
    // tokenize.pl passes these; the binary must accept them and still succeed
    let out = run_args(&["--language=Rust", "--position", "--verbose", "tests/fixtures/hello.rs"]);
    assert!(out.status.success(), "binary exited with {}", out.status);
    assert!(String::from_utf8_lossy(&out.stdout).contains("\tkeyword|fn"));
}
