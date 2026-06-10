# `generate_dataset.py` — Detailed Code Walkthrough

## Structure overview

The script has **4 phases** connected sequentially:

```
blame files + source files  →  SQLite (token_map)  →  DuckDB JOIN  →  Parquet
     (Phase 1: sync)                (Phase 2: enrich & write)
```

Plus 3 support layers: **SourceReader**, **Skip functions**, **Blame parser**.

---

## 1. SourceReader (lines 30–65)

Replicates `Read_Src_Char` from `prettyPrint-author.pl`. It walks through a source file character by character, tracking `(line, col)` position.

```python
class SourceReader:
    def __init__(self, source_text: str):
        self.source = source_text
        self.pos = 0
        self.line = 1
        self.col = 1
        self._prev_col = 0      # saved col for unread after newline
        self._last_char = None   # buffer for unread_char
```

### `read_char()`

Returns next char, or `None` at EOF. Two branches:
- If `_last_char` is set (from `unread_char`), return that instead of advancing `pos`
- Otherwise read `source[pos]` and advance `pos`
- Both branches update `(line, col)` after consuming the char
- `\n` increments `line`, resets `col=1` (saves old col to `_prev_col`)
- Other chars increment `col`

### `unread_char(ch)`

Pushes a char back into the buffer (`_last_char`). Reverses the position tracking:
- `\n`: decrements `line`, restores `col` from `_prev_col`
- Other: decrements `col`

### `location()`

Returns current `(line, col)` tuple.

### Why this exists

The CreGit tokenizer splits source into fine-grained tokens (keywords, identifiers, punctuation, etc.), but the token stream has **no position info** — only the token value. SourceReader re-synchronizes by replaying the source file alongside the token stream, consuming the same characters that each token represents.

---

## 2. Skip functions (lines 73–142)

Four functions that consume characters from the source for a given token. They handle the character-by-character matching between the token value (from the blame file) and the actual source text.

### `skip_token(token_value, reader)` — line 81

For simple tokens (identifiers, keywords, punctuation, operators). Strips whitespace from the token value, then consumes that many **non-whitespace** characters from the source:

```python
stripped = re.sub(r"\s", "", token_value)
remaining = len(stripped)
while remaining > 0:
    ch = reader.read_char()
    text += ch
    if not is_ws(ch):
        remaining -= 1
```

This skips whitespace in the source that might not be present in the token value (the tokenizer normalizes whitespace).

### `skip_comment(token_value, reader)` — line 95

Handles comment tokens where the source and token value may differ (e.g., the tokenizer may preprocess comment text). It:
1. Reads all leading whitespace from source
2. Compares each non-whitespace char from `token_value` against the source char
3. If they don't match (and it's not a space-vs-newline case), logs a **WARNING** (not `assert`)
4. Interleaves whitespace from source between comparisons

The key difference from `skip_token`: comments are matched **character-by-character sequentially**, not just by length. This catches cases where the tokenizer transforms comment content.

### `skip_literal(token_value, reader)` — line 113

Similar to `skip_comment` but for string/character literals. Also does char-by-char comparison with warnings on mismatch. Handles whitespace normalization: if the token value has a non-ws char and source has ws, it reads all source ws before continuing.

### `skip_whitespace(reader)` — line 133

Reads and discards all consecutive whitespace chars from the source (spaces, tabs, newlines). Called after every non-structural token to advance past inter-token whitespace.

### Why these exist

The tokenizer's token values don't perfectly match the source text in all cases (especially for comments and literals where the tokenizer may reformat). These functions approximate "what source text does this token cover" by replaying through the source.

---

## 3. `classify_and_skip(token_content, reader)` — line 145

The central dispatcher. Takes a raw token content string from the blame file and returns a dict of metadata:

```python
{
    "token_type": ...,
    "token_value": ...,
    "source_text": ...,
    "source_line": before_line,
    "source_col": before_col,
    "is_structural": 0 or 1,
    "func_name": None or "name"
}
```

### Classification rules (checked in order)

| Condition | token_type | is_structural | source consumed? |
|---|---|---|---|
| starts with `begin_unit` | `"begin_unit"` | 1 | No |
| in `("begin_function", "end_function")` | same as content | 1 | No |
| starts with `DECL\|` | `"DECL"` | 1 | No, `func_name` extracted from last `\|`-delimited part |
| `== "\|"` | `""` (empty) | 1 | No |
| empty string | `"blank"` | 1 | No |
| matches `^(begin\|end)_[a-z_]+$` | the full marker name | 1 | No |
| matches `^(.+?)\|(.*)$` | first group (`type`) | 0 | Yes — via `skip_*` |
| **none of the above** | `"unknown"` | 1 | No |

Structural tokens consume **no source text** — they're injected by srcML/metadata, not actual code characters. Non-structural tokens (`comment`, `literal`, `identifier`, etc.) consume source via the appropriate skip function, then skip trailing whitespace via `skip_whitespace`.

---

## 4. `parse_blame_line(line)` — line 234

Parses a single line from the blame file. Format:

```
<commit_sha>;<author_idx>\t<token_content>
```

Splits on `;` (max 2 splits), giving `[sha, author_idx_or_empty, <tab> + token_content]`. Extracts:
- `commit_sha = parts[0]`
- `token_content = parts[2].lstrip("\t")` (strips the leading tab)
- Returns `None` for empty lines

---

## 5. `process_blame_file(blame_path, source_path, rel_path, db_cursor)` — line 246

Processes one file pair (blame + source). For each blame line:
1. `parse_blame_line` extracts `(commit_sha, token_content)`
2. `classify_and_skip` classifies the token and advances the SourceReader through the source file
3. Inserts a row into `token_map` SQLite table with all metadata

Returns the count of tokens processed.

---

## 6. `main()` — line 294

### Phase 1 — Sync (lines 352–406)

- Creates a **temporary SQLite database** (`sync_*.db`) with a `token_map` table
- Walks all `.blame` files in the blame directory
- For each, finds the matching source file under `--source-dir`
- Calls `process_blame_file` to populate `token_map`
- Columns: `file_path, token_index, commit_sha, token_type, token_value, source_text, source_line, source_col, is_structural, func_name`

### Phase 2 — DuckDB JOIN → Parquet (lines 408–487)

Attaches **3 SQLite databases** via `sqlite_scanner`:

| Database | Source | Contains |
|---|---|---|
| `sync_db` | Built in Phase 1 | `token_map` |
| `cregit.db` | Step 5 pipeline output | `commits` table (commit metadata), `commitmap` (cregit → original SHA mapping) |
| `persons.db` | Step 6 pipeline output | `emails` table, `persons` table (author identity) |

Runs this JOIN query:

```sql
SELECT
    repo_name, file_path, token_index, source_line, source_col, source_text,
    token_type, token_value, is_structural,
    t.commit_sha                  AS cregit_commit_sha,
    COALESCE(m.originalcid, t.commit_sha) AS original_commit_sha,
    c.autname                     AS author_name,
    c.autemail                    AS author_email,
    c.autdate                     AS author_date,
    c.comname                     AS committer_name,
    c.comemail                    AS committer_email,
    c.comdate                     AS committer_date,
    c.summary                     AS commit_summary,
    e.personid,
    COALESCE(p.personname, e.personid) AS person_name,
    e.emailaddr                   AS person_email,
    e.domain                      AS person_domain,
    COALESCE(m.repo, '')          AS repo_tag
FROM token_map t
JOIN commits c              ON t.commit_sha = c.cid
LEFT JOIN commitmap m       ON c.cid = m.cid
LEFT JOIN emails e          ON (c.autname = e.emailname AND c.autemail = e.emailaddr)
LEFT JOIN persons p         ON e.personid = p.personid
ORDER BY t.file_path, t.token_index
```

Writes the result directly to Parquet with ZSTD compression. Prints final row count. Cleans up the temp SQLite DB.

---

## Data flow summary

```
.blame files ──┐
               ├── parse_blame_line() ──> (sha, token_content)
               │                              │
               │                    classify_and_skip(token_content, SourceReader)
               │                              │
               │                     ┌─── begin_unit ──> structural, no source consumed
               │                     ├─── begin_function / end_function ──> structural
               │                     ├─── DECL ──> structural + func_name
               │                     ├─── | ──> structural delimiter
               │                     ├─── "" ──> blank, structural
               │                     ├─── begin_*/end_* ──> structural markers
               │                     ├─── type|value ──> non-structural (skip_token/comment/literal)
               │                     └─── fallthrough ──> unknown structural
               │                              │
               └── INSERT INTO token_map ─────┘
                            │
               DuckDB JOIN (token_map × commits × commitmap × emails × persons)
                            │
                        .parquet file
```

---

## Output columns (Parquet schema)

| Column | Type | Description |
|---|---|---|
| `repo_name` | TEXT | Repository name (e.g. `jq`) |
| `file_path` | TEXT | Relative path in source tree (e.g. `src/builtin.c`) |
| `token_index` | INTEGER | Sequential index within the file (0-based) |
| `source_line` | INTEGER | Line number in original source |
| `source_col` | INTEGER | Column number in original source |
| `source_text` | TEXT | The source text consumed by this token |
| `token_type` | TEXT | Classification: `identifier`, `keyword`, `literal`, `comment`, `punctuation`, `DECL`, `begin_*`, `end_*`, `blank`, etc. |
| `token_value` | TEXT | The token value as stored in the blame file |
| `is_structural` | INTEGER | 1 = structural annotation (no source text), 0 = real source token |
| `cregit_commit_sha` | CHAR(40) | Commit SHA from the cregit (post-BFG) repo |
| `original_commit_sha` | CHAR(40) | Mapped original commit SHA (unmodified repo) |
| `author_name` | TEXT | Commit author name |
| `author_email` | TEXT | Commit author email |
| `author_date` | TEXT | Commit author date |
| `committer_name` | TEXT | Committer name |
| `committer_email` | TEXT | Committer email |
| `committer_date` | TEXT | Committer date |
| `commit_summary` | TEXT | Commit message summary (first line) |
| `personid` | TEXT | Person identifier from persons DB |
| `person_name` | TEXT | Resolved person name |
| `person_email` | TEXT | Resolved person email |
| `person_domain` | TEXT | Email domain |
| `repo_tag` | TEXT | Repository tag from commitmap (or empty) |
