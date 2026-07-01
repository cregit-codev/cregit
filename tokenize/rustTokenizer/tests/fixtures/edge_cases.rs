use std::collections::HashMap;

fn lifetimes<'a, 'b: 'a>(s: &'a str) -> &'b str { s }

fn literals() {
    let _a = 1_000_000u64;
    let _b = 2.5f64;
    let _c = 'x';
    let _d = b'y';
    let _e = "with \n newline";
    let _f = r#"raw "quoted" string"#;
    let _g = b"bytes";
    let _h = br#"raw bytes"#;
}

// raw identifier and unicode identifier
fn r#type() {}
fn café() {}

/* outer /* inner */ outer */
struct Wrap<T> where T: Clone {
    inner: T,
}
