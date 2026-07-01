# Combined Knowledge Share — Presentation Artifact

**Proposal deadline: 2026-07-01**

---

## Two contributions

### 1. Bug fix: BFG repo-cleaner silently drops footers from cregit output

**The problem:** JGit's `CommitMessage.getFooterLines()` parses trailers bottom-up
and **stops at the first blank line**. BFG repo-cleaner appends
`\nFormer-commit-id: <sha>` to every commit message. When the original message
already ends with `\n` (most do), the result is:

```
Co-authored-by: Name <email>\n
\n
Former-commit-id: <sha>\n
```

JGit only sees `Former-commit-id` — all preceding footers (`Co-authored-by`,
`Signed-off-by`, etc.) are silently dropped. The cregit Parquet dataset gets
empty footer arrays for those commits.

**The fix:** `scripts/fix_missing_footers.py` (pipeline step 5b) post-hoc merges
missing footers from `{name}-original.db` into `{name}-cregit.db` using the
`Former-commit-id` value as the join key. The fix is idempotent — re-running
does not duplicate footers.

**Impact on jq:**
| Footer type | Before fix | After fix |
|---|---|---|
| Co-authored-by | 2 commits | 7 commits |
| Signed-off-by | 4 commits | 17 commits |
| Reported-by | 1 commit | 2 commits |
| Any footer | ~9 commits | 26 commits |

**Why it matters for the thesis:** The kernel subsystem datasets (iio, amd, net)
were also processed by BFG. If they have the same blank-line issue, every footer
count in the published Signal 4 results is an undercount.

---

### 2. Combined Knowledge Share — integrating token authorship + review participation

**Core idea:** For each token in the Parquet dataset, treat *both* the token author
and the commit's footer participants as "knowing" that token. Award 1 knowledge
point per token, split equally among all participants (author + co-authors +
reviewers + signers-off). Then recompute concentration on the combined distribution.

```
token: "int"  in  src/parser.c
  author:    stephen@practi.net
  footers:   Dirk Müller <dirk@dmllr.de>  (Co-authored-by)

Even split:  practi.net gets 0.5, dmllr.de gets 0.5
```

**Self-contained:** uses only columns already in the Parquet dataset
(`person_domain` + footer_* arrays). No external affiliation maps.
Grouped by email domain.

**Script:** `generate_dataset/combined_knowledge.py`

**Command:**
```bash
python3 generate_dataset/combined_knowledge.py \
    --parquet ../cregit-test2/jq-dataset.parquet --name jq
```

**Result on jq:**

| Metric | Pure tokens | Combined | Delta |
|--------|------------|----------|-------|
| HHI | 0.306 | 0.308 | +0.002 |
| Gini | 0.931 | 0.941 | +0.010 |
| Effective firms | 3.27 | 3.25 | -0.02 |
| Mass TF | 2 | 2 | 0 |

jq shows minimal change because only 2.27% of tokens have footers. This validates
that the method doesn't artificially inflate concentration when there's no data.

Domains that gain: **cryptonector.com** (+0.95pp, appears as co-author on others'
commits). Domains that lose: **gmail.com** (-0.91pp, their commits have co-authors).

**The real test:** kernel subsystems where footer participation is substantial
(iio: 23,806 trailers, amd: 121,474). The combined measure will show whether
concentration is amplified or diluted when review participation is counted.

---

### Future work

1. **Run on kernel datasets** — verify whether the BFG bug affects iio/amd/net.
   If so, apply the fix. Then run combined_knowledge.py on all three.

2. **Weight sensitivity analysis** — the even split is conservative. Test an
   alternative: author gets 0.5, reviewers split 0.5.

3. **Company-level combined share** — use the gitdm affiliation map to aggregate
   domains → companies, then compute combined concentration at firm level.

4. **Notebook integration** — add a §2c to the OVERVIEW notebook showing the
   pure vs combined concentration table across all subsystems.

### Files created/modified

| File | Location | Purpose |
|------|----------|---------|
| `scripts/fix_missing_footers.py` | cregit repo | Post-hoc footer merge from original.db → cregit.db |
| `generate_dataset/combined_knowledge.py` | cregit repo | Token-level S3+S4 integration script |
| `generate_dataset/COMBINED_KNOWLEDGE.md` | cregit repo | Methodology documentation |
| `run_pipeline_process.sh` | cregit repo | Added step 5b (fix_missing_footers) |
