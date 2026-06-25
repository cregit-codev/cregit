// Tokenizer for the Rust language, used by the cregit pipeline.
//
// Produces the same line-oriented token stream as cregit/tokenize/goTokenizer/gotoken.go,
// so that blameRepo and prettyPrint can consume Rust files exactly like Go files.
//
// Output format:
//   -:-\tbegin_unit|revision:...;language:Rust;cregit-version:...
//   LINE:COL\tkind|"value"     (tokens with semantic content: idents, literals, comments, lifetimes)
//   LINE:COL\tlexeme           (punctuation and operators, printed verbatim)
//   -:-\tend_unit
//
// The lexer (rustc_lexer) is the same one rustc uses, so corner cases like raw strings,
// byte literals, lifetimes vs. char literals, and unicode identifiers are handled correctly.
//
// CLI: rust_tokenizer [--language=Rust] [--position] [--verbose] <source.rs>
// Flags --language/--position/--verbose are accepted (and ignored, except --verbose) so the
// dispatcher in tokenize.pl can call this binary the same way it calls the others.

use rustc_lexer::{tokenize, TokenKind};
use std::env;
use std::fs;
use std::process::exit;

const REVISION: &str = "0.0.1";
const CREGIT_VERSION: &str = "0.0.1";

// Strict keywords in Rust 2021. Anything in this set is emitted as `keyword|"..."`
// instead of `identifier|"..."`. rustc_lexer does not split keywords from identifiers.
const KEYWORDS: &[&str] = &[
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "union", "unsafe", "use", "where", "while",
];

fn main() {
    let path = parse_args();
    let src = match fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("rust_tokenizer: cannot read {}: {}", path, e);
            exit(1);
        }
    };
    tokenize_source(&src);
}

fn parse_args() -> String {
    let mut path: Option<String> = None;
    for arg in env::args().skip(1) {
        // accept and ignore the flags the dispatcher passes
        if arg.starts_with("--language=") || arg == "--position" || arg == "--verbose" {
            continue;
        }
        if arg.starts_with("--") {
            eprintln!("rust_tokenizer: unknown option [{}]", arg);
            exit(2);
        }
        if path.is_none() {
            path = Some(arg);
        } else {
            eprintln!("rust_tokenizer: extra positional argument [{}]", arg);
            exit(2);
        }
    }
    path.unwrap_or_else(|| {
        eprintln!("Usage: rust_tokenizer [--language=Rust] <source.rs>");
        exit(2);
    })
}

fn tokenize_source(src: &str) {
    println!(
        "-:-\tbegin_unit|revision:{};language:Rust;cregit-version:{}",
        REVISION, CREGIT_VERSION
    );

    let mut byte = 0usize;
    let mut line = 1usize;
    let mut col = 1usize;

    for tok in tokenize(src) {
        let end = byte + tok.len;
        let slice = &src[byte..end];

        if let Some(out) = classify(&tok.kind, slice) {
            println!("{}:{}\t{}", line, col, out);
        }

        // advance position cursor through this token's text
        for ch in slice.chars() {
            if ch == '\n' {
                line += 1;
                col = 1;
            } else {
                col += 1;
            }
        }
        byte = end;
    }

    println!("-:-\tend_unit");
}

// Decide what to print for a token. Returns None when the token should be skipped
// (whitespace, which has no semantic content but still advances the cursor).
//
// Format note: the value after the `|` is emitted **verbatim** — no quoting, no
// escaping. This is what `prettyPrint-author.pl` expects (it walks the source
// character-by-character matching it against the token value via Skip_Token /
// Skip_Literal / Skip_Comment). It is also what `srcml2token` emits (e.g.
// `literal|0`, `name|main`).
fn classify(kind: &TokenKind, slice: &str) -> Option<String> {
    use TokenKind::*;
    match kind {
        // ignored, but cursor still advances in the caller
        Whitespace => None,

        // idents may be keywords
        Ident | RawIdent => {
            let label = if KEYWORDS.contains(&slice) { "keyword" } else { "identifier" };
            Some(format!("{}|{}", label, slice))
        }

        // 'a, 'static — lifetime starts with single quote
        Lifetime { .. } => Some(format!("lifetime|{}", slice)),

        // All literal subkinds use the singular `literal` type so that
        // prettyPrint's Skip_Literal handler picks them up — Skip_Literal
        // tolerates whitespace inside the value (relevant for multi-line raw
        // strings), which Skip_Token does not. Newlines inside the value
        // would also break the line-based output format, so they are folded
        // to spaces (Skip_Literal compares ignoring whitespace).
        Literal { .. } => Some(format!("literal|{}", flatten(slice))),

        // Block comments are commonly multi-line; both Skip_Comment and the
        // line-based token stream require us to fold newlines. srcml2token
        // does the same.
        LineComment => Some(format!("comment|{}", slice)),
        BlockComment { .. } => Some(format!("comment|{}", flatten(slice))),

        // Punctuation and operators: emit `op|<lexeme>`. The `op|` prefix is
        // mandatory — prettyPrint-author.pl skips any token line that does not
        // contain `|`, which would leave its source cursor un-advanced and
        // desync the whole stream.
        // Multi-char operators (==, ->, ::) come out as separate single-char
        // tokens. That is fine for blame: each source character is still
        // attributed exactly once, and operator grouping does not change
        // per-token authorship.
        Semi | Comma | Dot | OpenParen | CloseParen | OpenBrace | CloseBrace
        | OpenBracket | CloseBracket | At | Pound | Tilde | Question | Colon | Dollar
        | Eq | Not | Lt | Gt | Minus | And | Or | Plus | Star | Slash | Caret | Percent => {
            Some(format!("op|{}", slice))
        }

        // Unknown / unrecognised bytes — also wrap with kind so prettyPrint
        // sees the `|` and consumes the source chars.
        Unknown => Some(format!("unknown|{}", slice)),
    }
}

// Replace embedded newlines/carriage returns with spaces so the value fits on a
// single output line. prettyPrint's Skip_Literal / Skip_Comment ignore
// whitespace when matching the value against the source, so the substitution is
// invisible to downstream consumers.
fn flatten(s: &str) -> String {
    s.replace(['\n', '\r'], " ")
}
