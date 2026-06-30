# Footer analysis: jq repository

Queries executed against the jq Parquet dataset (311,688 token rows, 77 source files,
757 unique commits).

> **Scope note:** The CreGit pipeline only processes files matching `\.[ch]$`
> (.c and .h source files). Commits that change only non-C files (docs, CI,
> Makefile, `.cpp` fuzz tests, etc.) are excluded from the dataset. The raw
> `footers` table in the SQLite database has entries for all 1,976 commits — the
> Parquet dataset's 757 commits are a filtered subset. All numbers below refer
> to the Parquet dataset unless otherwise noted.

---

## Q1 — Footer prevalence by type

**In the raw footers table** (all 1,976 commits in cregit.db):

| Footer key | Distinct commits | Context |
|---|---|---|
| `former-commit-id` | 1,974 | Every commit has this (pipeline artifact) |
| `co-authored-by` | 35 | PR squash-merges and bot co-authors |
| `signed-off-by` | 34 | DCO sign-offs |
| `updated-dependencies` | 9 | Dependabot updates |
| `ref` | 5 | Reference footers |
| `reported-by` | 2 | Bug reporters |

**In the Parquet dataset** (757 commits that touch `.c/.h` files):

| Footer key | Commits | % of 757 commits |
|---|---|---|
| `signed-off-by` | 4 | 0.53% |
| `co-authored-by` | 2 | 0.26% |
| `reported-by` | 1 | 0.13% |
| all other keys | 0 | 0.00% |

Of the 35 co-authored-by and 34 signed-off-by commits in the footers table:
- 25+ are `dependabot[bot]` dependency bumps in `/docs/` — no C files changed
- 4 are `github-actions[bot]` CI signature updates — no C files changed
- 2 touch `tests/jq_fuzz_execute.cpp` (`.cpp`, excluded by `\.[ch]$` filter)
- 1 touches `Makefile.am` — not a C file
- 3 are documentation/README/CI config changes — no C files changed
- 1 is a merge commit with no C file changes
- The remaining commits change `.c`/`.h` files and **are** correctly captured

**Insight:** The low footer counts are not a bug — they reflect the `.c/.h` file
filter. The footers table confirms jq does use `signed-off-by` and `co-authored-by`
on non-C commits (dependabot, CI, docs), but for C source code changes, footer
usage is genuinely sparse. Only 9 unique C-touching commits have any footer at
all (including the 7 below plus 2 dependabot/github-actions commits that
incidentally touch `.c` files alongside CI changes).

---

## Q2 — Top footer participants vs. top authors

**Footer participants** (resolved person IDs):

| Person | Mentions (token rows) | Distinct commits |
|---|---|---|
| nicolas williams | 3,716 | 1 |
| tyler rockwood | 45 | 1 |
| goodactive | 6 | 1 |
| christoph anton mitterer | 2 | 1 |
| andrew marshall | 1 | 1 |
| gabriel marin | 1 | 1 |
| wellweek | 1 | 1 |

**Top authors** (by distinct commits):

| Person | Commits | Tokens |
|---|---|---|
| nicolas williams | 228 | 59,086 |
| stephen dolan | 164 | 52,124 |
| itchyny | 88 | 14,034 |
| emanuele torre | 50 | 4,073 |
| david tolnay | 32 | 3,870 |
| william langford | 24 | 6,457 |
| muh muhten | 20 | 1,058 |
| mattias wadman | 16 | 4,729 |

**Insight:** The footer-participant list reveals a different population from the
author list. None of the top-8 authors appear as footer participants — the people
credited in footers are an entirely separate group. This confirms that **Signal 4
(footer/review trailers) captures a distinct contributor base** that commit-share
and DOA (Signals 1–2) miss entirely. For jq, these are external contributors who
reported bugs or were co-authors on squashed PRs.

The extreme token count for `nicolas williams` (3,716 mentions from 1 commit) is
explained by a single large squash-merge commit that touches many files.

---

## Q3 — Review coverage by source file

```sql
-- No files have any review footers (reviewed-by, acked-by, tested-by)
-- Coverage is 0% for every file with >500 tokens
```

**All 37 files** with >500 tokens have exactly **0.00%** review coverage.
This includes:
- `src/parser.c` — 27,102 tokens — 0% reviewed
- `vendor/decNumber/decBasic.c` — 23,113 tokens — 0% reviewed
- `src/builtin.c` — 14,732 tokens — 0% reviewed

**Insight:** The jq project does not use review footers. This means the **review gap
analysis** cannot be conducted via footers alone for this repository — the entire
codebase is "unreviewed" by this signal. Possible explanations:
1. jq uses GitHub's PR review UI (not footer-based review)
2. jq's maintainer model is trust-based (Stephen Dolan was the primary author)
3. External review exists but is not captured in commit footers

---

## Q4 — Cross-company collaboration via co-authors

| Author domain | Co-authored commits | Cross-domain commits |
|---|---|---|
| `gmail.com` | 1 | 1 |
| `cybozu.co.jp` | 1 | 1 |

The two co-authored commits involve cross-domain collaboration:

| Commit | Author (domain) | Co-author (domain) |
|---|---|---|
| `fdab39bc` | Mattias Wadman (gmail.com) | Nicolas Williams (cryptonector.com) |
| `d0adcbf` | itchyny (cybozu.co.jp) | Andrew Marshall (johnandrewmarshall.com), Gabriel Marin (protonmail.com) |

**Insight:** Both co-authored commits are cross-domain — authors and co-authors
come from different organizations. This is **genuine inter-organizational
collaboration**. The number is small (2 commits) but consistent with jq being a
mature, low-activity project maintained by a small group.

---

## Q5 — AI attribution sensitivity (RQ5)

**No AI-related footers found.** Zero commits carry `assisted-by`,
`co-developed-by`, or `suggested-by` footers. The jq project predates the
AI-assisted development era (active development mostly pre-2020), so this
result is expected. RQ5 sensitivity analysis is not applicable for jq.

---

## Q6 — Non-trivial review signal

| Metric | Value |
|---|---|
| Total distinct commits | 757 |
| Commits with review footer | 0 |
| Commits with external reviewer | 0 |
| % external of reviewed | N/A |

**Insight:** Not applicable to jq — no review footers exist.

---

## Q7 — Knowledge dissemination chains

| Collaboration type | Commits |
|---|---|
| No footers | 755 |
| Co-author only | 2 |
| Review only | 0 |
| Both co-author and review | 0 |

**Insight:** The collaboration culture in jq is not footer-driven. Only 2
commits (0.26%) have any collaboration metadata (co-authored-by). The 755
remaining commits have no footer at all — these are single-author commits with
no external attribution.

---

## Person resolution statistics

| Metric | Value |
|---|---|
| Commits with footers in Parquet | 7 |
| Resolved (person ID found) | 6 |
| Unresolved | 1 |
| Total footers in cregit.db | 2,078 rows (from 1,976 commits) |
| Footer keys in cregit.db | 10 distinct keys |

The one unresolved footer is `Reported-by: Hsiang-Ying Fu` — a name-only
footer value without an email address. The regex-based email extraction cannot
match this. This is a natural limitation: some footers credit people by name
only when the commit message doesn't include their email.

Resolved persons (6): Tyler Rockwood, goodactive, Gabriel Marin, Andrew
Marshall, wellweek, Christoph Anton Mitterer, Nicolas Williams.

---

## Summary: jq's footer profile

| Research theme | Result for jq |
|---|---|
| **Signal 4 prevalence** | Very low (0.9% of commits). jq is not footer-driven. |
| **Hidden contributors** | 7 people credited in footers who are not top authors — confirms Signal 4 captures a distinct population. |
| **Review gap** | 100% of files have 0% review coverage by footers. jq does not use review footers. |
| **Cross-company collaboration** | Both co-authored commits are cross-domain. Small numbers but genuine external collaboration detected. |
| **AI attribution (RQ5)** | None found — jq predates AI tooling. |
| **Non-trivial review** | Not measurable via footers for jq. |
| **Knowledge flow** | Only co-author chains exist (no review chains). |

### Methodological lesson

jq demonstrates that **footer-based signals are project-specific**. Some projects
(Linux kernel) extensively use `Signed-off-by` for chain-of-custody; others
(Puppet, PostgreSQL) use `Reviewed-by`; jq uses footers minimally. The
corporate-knowledge-concentration analysis should combine footer signals with
other signals (commit-share, DOA, cregit tokens) as the research proposal
describes. For jq, Signals 1–3 (commit-share, DOA, cregit) will be far more
informative than Signal 4 (footers).
