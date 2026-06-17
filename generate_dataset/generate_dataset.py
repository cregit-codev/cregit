#!/usr/bin/env python3
"""Post-processing script: generate unified Parquet dataset from CreGit pipeline outputs.

#
# This Python implementation replicates token-matching logic from:
#   prettyPrint/prettyPrint-author.pl
#
# Keep the following in sync with the Perl original:
#
#   SourceReader      <-> Read_Src_Char / Un_Read_Char / Location / Skip_Whitespace  (Perl: lines 690-753)
#   skip_token        <-> Skip_Token      (Perl: line 669)
#   skip_comment      <-> Skip_Comment    (Perl: line 598)
#   skip_literal      <-> Skip_Literal    (Perl: line 517)
#   skip_whitespace   <-> Skip_Whitespace (Perl: line 736)
#   classify_and_skip <-> main loop       (Perl: lines 350-408)
#

Usage:
  uv run python3 generate_dataset/generate_dataset.py \
      --blame-dir  ../cregit-files/blame \
      --source-dir ../cregit-files/jq-original \
      --cregit-db  ../cregit-files/jq-cregit.db \
      --persons-db ../cregit-files/jq-persons.db \
      --output     ../cregit-files/jq-dataset.parquet
"""

import argparse
import logging
import os
import re
import sqlite3
import sys
import tempfile
from pathlib import Path

logger = logging.getLogger(__name__)


# ===================================================================
# SourceReader — replicates prettyPrint-author.pl's Read_Src_Char
# ===================================================================


class SourceReader:
    # Perl equivalent: Read_Src_Char / Un_Read_Char / Location (prettyPrint-author.pl:702)
    def __init__(self, source_text: str):
        self.source = source_text
        self.pos = 0
        self.line = 1
        self.col = 1
        self._prev_col = 0
        self._last_char: str | None = None

    def read_char(self) -> str | None:
        if self._last_char is not None:
            ch = self._last_char
            self._last_char = None
        elif self.pos >= len(self.source):
            return None
        else:
            ch = self.source[self.pos]
            self.pos += 1
        if ch == "\n":
            self.line += 1
            self._prev_col = self.col
            self.col = 1
        else:
            self.col += 1
        return ch

    def unread_char(self, ch: str) -> None:
        self._last_char = ch
        if ch == "\n":
            self.line -= 1
            self.col = self._prev_col
        else:
            self.col -= 1

    def location(self) -> tuple[int, int]:
        return (self.line, self.col)


# ===================================================================
# Skip functions — replicate prettyPrint-author.pl's Skip_*
# ===================================================================


def is_ws(ch: str | None) -> bool:
    return ch is not None and ch in " \t\n\r"


def consume(s: str) -> tuple[str, str]:
    return s[0], s[1:]


def skip_token(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Token (prettyPrint-author.pl:669)
    text = ""
    stripped = re.sub(r"\s", "", token_value)
    remaining = len(stripped)
    while remaining > 0:
        ch = reader.read_char()
        if ch is None:
            break
        text += ch
        if not is_ws(ch):
            remaining -= 1
    return text


def skip_comment(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Comment (prettyPrint-author.pl:598)
    text = ""
    while token_value:
        ch = reader.read_char()
        while ch is not None and is_ws(ch):
            text += ch
            ch = reader.read_char()
        if ch is None:
            break
        cT, token_value = consume(token_value)
        while is_ws(cT) and token_value:
            cT, token_value = consume(token_value)
        text += ch
        if (cT != ch) and not (cT == " " and ch == "\n"):
            logger.warning("Comment mismatch: token=%r source=%r", cT, ch)
    return text


def skip_literal(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Literal (prettyPrint-author.pl:517)
    text = ""
    while token_value:
        cT, token_value = consume(token_value)
        ch = reader.read_char()
        if ch is None:
            break
        if is_ws(ch) and not is_ws(cT):
            while ch is not None and is_ws(ch):
                text += ch
                ch = reader.read_char()
        if not is_ws(ch) and is_ws(cT):
            while token_value and is_ws(cT):
                cT, token_value = consume(token_value)
        text += ch
        if (cT != ch) and not (cT == " " and ch == "\n"):
            logger.warning("Literal mismatch: token=%r source=%r", cT, ch)
    return text


def skip_whitespace(reader: SourceReader) -> str:
    # Perl equivalent: Skip_Whitespace (prettyPrint-author.pl:736)
    text = ""
    while True:
        ch = reader.read_char()
        if ch is None or not is_ws(ch):
            if ch is not None:
                reader.unread_char(ch)
            break
        text += ch
    return text


def classify_and_skip(token_content: str, reader: SourceReader) -> dict:
    # Perl equivalent: token-classification logic in main loop (prettyPrint-author.pl:350)
    before_line, before_col = reader.location()

    if token_content.startswith("begin_unit"):
        return {
            "token_type": "begin_unit",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if token_content in ("begin_function", "end_function"):
        return {
            "token_type": token_content,
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if token_content.startswith("DECL|"):
        parts = token_content.split("|")
        return {
            "token_type": "DECL",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": parts[-1] if len(parts) >= 3 else None,
        }

    if token_content == "|":
        return {
            "token_type": "",
            "token_value": "",
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if not token_content:
        return {
            "token_type": "blank",
            "token_value": "",
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if re.match(r"^(begin|end)_[a-z_]+$", token_content):
        return {
            "token_type": token_content,
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    m = re.match(r"^(.+?)\|(.+)$", token_content)
    if not m:
        return {
            "token_type": "unknown",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    tok_type = m.group(1)
    tok_value = m.group(2)

    if tok_type == "comment":
        source_text = skip_comment(tok_value, reader)
    elif tok_type == "literal":
        source_text = skip_literal(tok_value, reader)
    else:
        source_text = skip_token(tok_value, reader)

    ws = skip_whitespace(reader)
    source_text += ws

    return {
        "token_type": tok_type,
        "token_value": tok_value,
        "source_text": source_text,
        "source_line": before_line,
        "source_col": before_col,
        "is_structural": 0,
        "func_name": None,
    }


# ===================================================================
# Blame file processing
# ===================================================================


def parse_blame_line(line: str) -> tuple[str, str] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    parts = line.split(";", 2)
    if len(parts) < 2:
        return None
    commit_sha = parts[0]
    token_content = parts[2].lstrip("\t") if len(parts) > 2 else ""
    return commit_sha, token_content


def process_blame_file(
    blame_path: Path, source_path: Path, rel_path: str, db_cursor
) -> int:
    with open(blame_path, encoding="utf-8", errors="replace") as f:
        blame_lines = f.readlines()

    with open(source_path, encoding="utf-8", errors="replace") as f:
        source_text = f.read()

    reader = SourceReader(source_text)
    count = 0

    for token_index, bline in enumerate(blame_lines):
        parsed = parse_blame_line(bline)
        if parsed is None:
            continue
        commit_sha, token_content = parsed

        info = classify_and_skip(token_content, reader)

        db_cursor.execute(
            """INSERT INTO token_map
               (file_path, token_index, commit_sha, token_type, token_value,
                source_text, source_line, source_col, is_structural, func_name)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                rel_path,
                token_index,
                commit_sha,
                info["token_type"],
                info["token_value"],
                info["source_text"],
                info["source_line"],
                info["source_col"],
                info["is_structural"],
                info["func_name"],
            ),
        )
        count += 1

    return count


# ===================================================================
# Main
# ===================================================================


def main():
    parser = argparse.ArgumentParser(
        description="Generate unified Parquet dataset from CreGit pipeline outputs"
    )
    parser.add_argument(
        "--blame-dir", required=True, help="Directory with .blame files (Step 8 output)"
    )
    parser.add_argument(
        "--source-dir", required=True, help="Original source tree root (jq-original/)"
    )
    parser.add_argument(
        "--cregit-db", required=True, help="Path to cregit.db (Step 5 output)"
    )
    parser.add_argument(
        "--persons-db", required=True, help="Path to persons.db (Step 6 output)"
    )
    parser.add_argument("--output", required=True, help="Output Parquet file path")
    parser.add_argument(
        "--repo-name",
        default="",
        help="Repository name (default: inferred from output filename)",
    )
    parser.add_argument(
        "--verbose", action="store_true", help="Verbose output (info-level logging)"
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(levelname)s: %(message)s",
        stream=sys.stderr,
    )

    blame_root = Path(args.blame_dir)
    source_root = Path(args.source_dir)
    output_path = Path(args.output)

    for p, label in [(blame_root, "blame-dir"), (source_root, "source-dir")]:
        if not p.is_dir():
            print(f"ERROR: {label} not found: {p}", file=sys.stderr)
            sys.exit(1)

    for p, label in [(args.cregit_db, "cregit-db"), (args.persons_db, "persons-db")]:
        if not Path(p).is_file():
            print(f"ERROR: {label} not found: {p}", file=sys.stderr)
            sys.exit(1)

    if not args.repo_name:
        args.repo_name = output_path.stem.replace("-dataset", "")

    blame_files = sorted(blame_root.rglob("*.blame"))
    if not blame_files:
        print("WARNING: no .blame files found", file=sys.stderr)

    print(f"Found {len(blame_files)} .blame files")
    print(f"Repo name: {args.repo_name}")
    print(f"Output:    {output_path}")

    # ------------------------------------------------------------------
    # Phase 1: sync blame → token_map (SQLite)
    # ------------------------------------------------------------------
    sync_db_fd, sync_db_path = tempfile.mkstemp(suffix=".db", prefix="sync_")
    os.close(sync_db_fd)

    sync_conn = sqlite3.connect(sync_db_path)
    sync_conn.execute("""
        CREATE TABLE IF NOT EXISTS token_map (
            file_path    TEXT,
            token_index  INTEGER,
            commit_sha   CHAR(40),
            token_type   TEXT,
            token_value  TEXT,
            source_text  TEXT,
            source_line  INTEGER,
            source_col   INTEGER,
            is_structural INTEGER,
            func_name    TEXT
        )
    """)
    sync_conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_tm_file ON token_map(file_path, token_index)"
    )

    cursor = sync_conn.cursor()
    total_tokens = 0
    files_processed = 0

    for bf in blame_files:
        rel = bf.relative_to(blame_root)
        rel_str = str(rel)
        if rel_str.endswith(".blame"):
            rel_str = rel_str[:-6]
        source_path = source_root / rel_str

        if not source_path.is_file():
            print(f"  WARNING: source not found: {source_path}, skipping {bf.name}")
            continue

        if args.verbose:
            print(f"  {bf.name} -> {rel_str}")
        count = process_blame_file(bf, source_path, rel_str, cursor)
        total_tokens += count
        files_processed += 1

    sync_conn.commit()
    sync_conn.close()

    print(f"Synced {files_processed} files, {total_tokens} tokens")

    if total_tokens == 0:
        print("ERROR: no tokens processed, nothing to output", file=sys.stderr)
        Path(sync_db_path).unlink(missing_ok=True)
        sys.exit(1)

    # ------------------------------------------------------------------
    # Phase 2: DuckDB JOIN → Parquet
    # ------------------------------------------------------------------
    try:
        import duckdb
    except ImportError:
        print(
            "ERROR: duckdb is required. Install with: uv pip install duckdb",
            file=sys.stderr,
        )
        Path(sync_db_path).unlink(missing_ok=True)
        sys.exit(1)

    con = duckdb.connect()
    con.execute("INSTALL sqlite_scanner; LOAD sqlite_scanner;")

    con.execute(f"CALL sqlite_attach('{sync_db_path}')")
    con.execute(f"CALL sqlite_attach('{args.cregit_db}')")
    con.execute(f"CALL sqlite_attach('{args.persons_db}')")

    query = f"""
        COPY (
            SELECT
                '{args.repo_name}'           AS repo_name,
                t.file_path,
                t.token_index,
                t.source_line,
                t.source_col,
                t.source_text,
                t.token_type,
                t.token_value,
                t.is_structural,

                t.commit_sha                  AS cregit_commit_sha,
                coalesce(m.originalcid, t.commit_sha) AS original_commit_sha,

                c.autname                     AS author_name,
                c.autemail                    AS author_email,
                c.autdate                     AS author_date,
                c.comname                     AS committer_name,
                c.comemail                    AS committer_email,
                c.comdate                     AS committer_date,
                c.summary                     AS commit_summary,

                e.personid,
                coalesce(p.personname, e.personid) AS person_name,
                e.emailaddr                   AS person_email,
                e.domain                      AS person_domain,

                coalesce(m.repo, '')          AS repo_tag

            FROM token_map t
            JOIN commits c                ON t.commit_sha = c.cid
            LEFT JOIN commitmap m         ON c.cid = m.cid
            LEFT JOIN emails e            ON (c.autname = e.emailname
                                         AND c.autemail = e.emailaddr)
            LEFT JOIN persons p           ON e.personid = p.personid

            ORDER BY t.file_path, t.token_index
        ) TO '{output_path}'
        (FORMAT PARQUET, COMPRESSION ZSTD)
    """

    print("Running JOIN query and writing Parquet...")
    try:
        con.execute(query)
    except Exception as e:
        print(f"ERROR during DuckDB query: {e}", file=sys.stderr)
        Path(sync_db_path).unlink(missing_ok=True)
        con.close()
        sys.exit(1)

    result = con.execute(f"SELECT COUNT(*) FROM read_parquet('{output_path}')")
    row_count = result.fetchone()[0]
    con.close()

    # Cleanup temp sync DB
    Path(sync_db_path).unlink(missing_ok=True)

    print(f"Done — {row_count:,} rows written to {output_path}")


if __name__ == "__main__":
    main()
