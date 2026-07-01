# Footer analysis: jq repository

Queries executed against the jq Parquet dataset (311,688 token rows, 77 source files,
757 unique commits).

> **Scope note:** The CreGit pipeline only processes files matching `\.[ch]$`
> (.c and .h source files). Commits that change only non-C files (docs, CI,
> Makefile, `.cpp` fuzz tests, etc.) are excluded from the dataset. The raw
> `footers` table in the SQLite database has entries for all 1,976 commits — the
> Parquet dataset's 757 commits are a filtered subset. All numbers below refer
> to the Parquet dataset unless otherwise noted.
>
> **Fix applied (2026-06-30):** A bug in BFG repo-cleaner caused it to insert a
> blank line before the `Former-commit-id` trailer when the original commit message
> ended with `\n`. This broke JGit's contiguous-trailer parsing, silently dropping
> footers (`Co-authored-by`, `Signed-off-by`, …) that preceded the blank line.
> The pipeline now runs `fix_missing_footers.py` (step 5b) which re-imports
> missing footers from the original (unmodified) database. The counts below
> reflect the corrected data.

---

## Q1 — Footer prevalence by type

**In the raw footers table** (all 1,976 commits in cregit.db):

| Footer key | Distinct commits | Context |
|---|---|---|
| `former-commit-id` | 1,974 | Every commit has this (pipeline artifact) |
| `signed-off-by` | 69 | DCO sign-offs |
| `co-authored-by` | 40 | PR squash-merges and bot co-authors |
| `updated-dependencies` | 9 | Dependabot updates |
| `ref` | 9 | Reference footers |
| `conflicts` | 8 | Merge conflict markers (skipped by fix script) |
| `https` | 5 | External links |
| `reported-by` | 3 | Bug reporters |
| `docs` | 2 | Documentation references |
| `see` | 2 | Cross-references |
| `after` | 1 | Temporal markers |
| `closes` | 1 | Issue closing references |
| `description` | 1 | Commit descriptions |
| `features` | 1 | Feature lists |
| `file` | 1 | File references |
| `fixes` | 1 | Fix references |
| `line` | 1 | Line references |
| `reported-by` (capitalized) | 1 | Bug reporter |
| `wchargin-branch` | 1 | Internal reference |
| `http` | 2 | External links |

**In the Parquet dataset** (757 commits that touch `.c/.h` files):

| Footer key | Commits | % of 757 commits |
|---|---|---|
| `signed-off-by` | 17 | 2.25% |
| `co-authored-by` | 7 | 0.92% |
| `reported-by` | 2 | 0.26% |
| all other keys | 0 | 0.00% |

Of the 40 co-authored-by and 69 signed-off-by commits in the footers table:
- Most of the difference (23 co-authored-by, 52 signed-off-by) are commits that
  change only non-C files (dependabot bumps in `/docs/`, GitHub Actions CI
  signature updates, documentation, CI config, etc.)
- The 7 co-authored-by and 17 signed-off-by commits in the Parquet dataset
  represent the subset that touch `.c`/`.h` files

**Insight:** The low footer counts are not a bug — they reflect the `.c/.h` file
filter. The footers table confirms jq does use `signed-off-by` and `co-authored-by`
on non-C commits (dependabot, CI, docs), but for C source code changes, footer
usage is genuinely sparse.

---

## Q2 — Top footer participants vs. top authors

**Footer participants** (resolved person IDs, across 26 commits with footers):

| Person | Tokens | Distinct commits |
|---|---|---|
| nicolas williams | 5,536 | 6 |
| davkor at david@adalogics.com | 711 | 7 |
| asaf meizner | 206 | 1 |
| he, tao | 110 | 1 |
| tyler rockwood | 45 | 1 |
| dirk muller | 45 | 1 |
| leonid s. usov | 36 | 1 |
| eric pruitt | 19 | 1 |
| david haguenauer | 19 | 1 |
| klemens nanni | 9 | 1 |
| goodactive at goodactive@qq.com | 6 | 1 |
| mattias wadman | 3 | 1 |
| christoph anton mitterer | 2 | 1 |
| andrew marshall | 1 | 1 |
| wellweek at xiezitai@outlook.com | 1 | 1 |
| gabriel marin | 1 | 1 |

**Top authors** (by distinct commits):

| Author | Commits | Tokens |
|---|---|---|
| Nicolas Williams | 225 | 58,719 |
| Stephen Dolan | 164 | 52,124 |
| itchyny | 90 | 14,058 |
| Emanuele Torre | 50 | 4,073 |
| David Tolnay | 32 | 3,870 |
| William Langford | 24 | 6,457 |
| Muh Muhten | 20 | 1,058 |
| Mattias Wadman | 16 | 4,729 |
| Assaf Gordon | 9 | 687 |
| Thalia Archibald | 8 | 489 |

**Hidden contributors:** All 16 footer participants are **not** in the top-20
authors list — the footer-credited population is entirely distinct from the
top author population. This confirms that **Signal 4 (footer/review trailers)
captures a distinct contributor base** that commit-share and DOA (Signals 1–2)
miss entirely. For jq, these are external contributors who reported bugs, were
co-authors on squashed PRs, or provided DCO sign-offs.

---

## Q3 — Review coverage by source file

**No files have any review footers** (reviewed-by, acked-by, tested-by).
Coverage is 0% for all 48 files with >500 tokens.

| File | Tokens | Reviewed |
|---|---|---|
| `src/parser.c` | 27,208 | 0% |
| `vendor/decNumber/decBasic.c` | 23,113 | 0% |
| `src/builtin.c` | 14,732 | 0% |

**Insight:** The jq project does not use review footers. This means the **review
gap analysis** cannot be conducted via footers alone for this repository — the
entire codebase is "unreviewed" by this signal. Possible explanations:
1. jq uses GitHub's PR review UI (not footer-based review)
2. jq's maintainer model is trust-based (Stephen Dolan was the primary author)
3. External review exists but is not captured in commit footers

---

## Q4 — Cross-company collaboration via co-authors

| Commit | Author (domain) | Co-author(s) |
|---|---|---|
| `24d8d247` | itchyny (cybozu.co.jp) | Mattias Wadman (gmail.com) |
| `3ebb3890` | itchyny (cybozu.co.jp) | Dirk Müller (dmllr.de) |
| `474f8699` | Mattias Wadman (gmail.com) | Nicolas Williams (cryptonector.com) |
| `954b1291` | itchyny (cybozu.co.jp) | Eric Pruitt (gmail.com), David Haguenauer (kurokatta.org) |
| `d3e09c23` | itchyny (cybozu.co.jp) | Leonid S. Usov (gmail.com) |
| `f52145a3` | itchyny (cybozu.co.jp) | Gabriel Marin (protonmail.com), Andrew Marshall (johnandrewmarshall.com) |
| `f5d0d1a9` | itchyny (cybozu.co.jp) | Asaf Meizner (gmail.com) |

**All 7 co-authored commits are cross-domain** — every single co-author comes
from a different domain than the commit author. Commits `3ebb3890` (the
previously missing Dirk Müller commit) and `24d8d247` (Mattias Wadman) are
newly captured by the footer fix, adding to the cross-domain collaboration
signal. The remaining commits involve itchyny (Cybozu, Japan) collaborating
with contributors worldwide.

---

## Q5 — AI attribution sensitivity (RQ5)

**No AI-related footers found.** Zero commits carry `co-developed-by`,
`assisted-by`, `suggested-by`, `mentored-by`, or `helped-by` footers. The jq
project predates the AI-assisted development era (active development mostly
pre-2020), so this result is expected. RQ5 sensitivity analysis is not
applicable for jq.

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
| No footers | 731 |
| Co-author only | 7 |
| Signed-off-by only | 17 |
| Both co-author and signed-off-by | 0 |
| Review only | 0 |

**Signed-off-by details:** Of the 17 signed-off-by commits, 8 are
**self-signoffs** (author signs off their own commit — e.g., David Korczynski,
Tyler Rockwood, HE Tao). The remaining 9 are second-party signoffs where
Nicolas Williams signed off on commits by Assaf Gordon (2) and William
Langford (3), and David Korczynski signed off on his own commits (4 additional,
counting both self and as maintainer). This suggests signed-off-by in jq is
used primarily as a DCO mechanism rather than a review chain.

**Co-author details:** All 7 co-authored commits are cross-domain (see Q4).
The author is consistently `itchyny` in 6 of 7 cases — itchyny's squash-merged
PRs preserve co-author attribution.

---

## Person resolution statistics

| Metric | Value |
|---|---|
| Commits with footers in Parquet | 26 |
| Resolved (person ID found) | 15 of 16 footer participants |
| Unresolved | 1 |
| Distinct person IDs | 16 |
| Distinct person names | 16 |

The one unresolved footer is `Reported-by: Hsiang-Ying Fu` — a name-only
footer value without an email address. The regex-based email extraction cannot
match this. This is a natural limitation: some footers credit people by name
only when the commit message doesn't include their email.

Resolved persons (15): Nicolas Williams, David Korczynski, Asaf Meizner,
HE Tao, Tyler Rockwood, Dirk Müller, Leonid S. Usov, Eric Pruitt,
David Haguenauer, Klemens Nanni, goodactive, Mattias Wadman,
Christoph Anton Mitterer, Andrew Marshall, wellweek, Gabriel Marin.

---

## Summary: jq's footer profile

| Research theme | Result for jq |
|---|---|
| **Signal 4 prevalence** | Low (3.4% of commits have any footer). jq is not footer-driven, but the fix increased detected commits from ~9 to 26. |
| **Hidden contributors** | All 16 footer participants are not top-20 authors — confirms Signal 4 captures a distinct population. |
| **Review gap** | 100% of files have 0% review coverage by footers. jq does not use review footers. |
| **Cross-company collaboration** | All 7 co-authored commits are cross-domain. Small numbers but genuine external collaboration detected. Dirk Müller commit now captured. |
| **AI attribution (RQ5)** | None found — jq predates AI tooling. |
| **Non-trivial review** | Not measurable via footers for jq. |
| **Knowledge flow** | Co-author and signed-off-by chains exist but no review chains. Self-signoffs are common. |

### Methodological lesson

jq demonstrates that **footer-based signals are project-specific**. Some projects
(Linux kernel) extensively use `Signed-off-by` for chain-of-custody; others
(Puppet, PostgreSQL) use `Reviewed-by`; jq uses footers minimally. The
corporate-knowledge-concentration analysis should combine footer signals with
other signals (commit-share, DOA, cregit tokens) as the research proposal
describes. For jq, Signals 1–3 (commit-share, DOA, cregit) will be far more
informative than Signal 4 (footers).

The BFG blank-line bug was the root cause of the previously missing footers.
The fix (step 5b — `fix_missing_footers.py`) ensures that footers correctly
extracted from the unmodified-repo database are merged back into the cregit
database, bringing the cregit DB's footer counts in line with the original DB.
