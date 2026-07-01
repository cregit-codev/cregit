# Combined Knowledge Share — Methodology

**Integrating token authorship (S3) with review/footer participation (S4)
at token granularity to measure firm-level knowledge concentration.**

## What it measures

The existing framework measures four separate signals:
- S1: commit-share (activity baseline)
- S2: DOA / truck factor (Avelino authorship-knowledge)
- **S3: cregit token ownership** (surviving-token authorship)
- **S4: mailing-list/review participation** (footer trailers)

S3 and S4 both measure "knowledge" but from different angles: S3 counts *who wrote* the
surviving code; S4 counts *who participated* in the commits that produced it (reviewers,
co-authors, signers-off). They are complementary — a person who reviews extensively but
writes little contributes knowledge that S3 alone misses.

The **Combined Knowledge Share** integrates S3 and S4 into a single measure that answers:
*When we treat both code authorship AND review participation as knowing the code, does
corporate concentration look the same as when we only count token ownership?*

## How it works

### Data source

The cregit Parquet dataset already has footer arrays per token row (from
`generate_dataset.py`'s LEFT JOIN to the `footers` table). Each row contains:

- `person_domain` — email domain of the token's author (S3)
- `footer_signed_off_by[]`, `footer_co_authored_by[]`, `footer_reviewed_by[]`,
  `footer_acked_by[]`, `footer_tested_by[]`, `footer_reported_by[]` — arrays of
  `"Name <email>"` strings from the commit's trailers (S4)

No joins or external data needed — both signals live in the same row.

### Per-token allocation rule

For each content token (`is_structural = 0`):

1. **Author domain** — from `person_domain` column
2. **Footer domains** — extract email from each footer entry via `"<...>"` regex,
   then extract domain from that email
3. **N** = |{author_domain} ∪ {footer_domains}| — number of unique domains
4. Award **1/N knowledge points** to each unique domain
5. If no footers, N = 1, author gets 1.0 point (identical to S3 baseline)

### Aggregation and metrics

Sum per-token points → domain-level combined knowledge share:
```
combined_share(domain) = 100 × sum_points(domain) / total_points
```

Compare against pure-token S3 baseline:
```
pure_share(domain) = 100 × tokens_written_by(domain) / total_tokens
```

Concentration metrics (HHI, Gini, effective firms) and mass truck factor are computed
on both distributions, then compared.

## Implementation

Script: `generate_dataset/combined_knowledge.py`

Self-contained: uses only columns present in the Parquet dataset. No external
affiliation maps or CSVs. Grouping is by email domain.

```bash
python3 generate_dataset/combined_knowledge.py \
    --parquet /path/to/jq-dataset.parquet \
    --name jq
```

Output: `{parquet_dir}/{name}-combined-knowledge.json`

## Output schema

```json
{
  "name": "iio",
  "total_tokens": 1934703,
  "tokens_with_footers": 29142,
  "pct_tokens_with_footers": 1.51,
  "pure_token_concentration": { "HHI": 0.306, "Gini": 0.931, "effective_firms": 3.27 },
  "combined_knowledge_concentration": { "HHI": 0.308, "Gini": 0.941, "effective_firms": 3.25 },
  "concentration_delta": { "HHI_delta": 0.002, "Gini_delta": 0.01, "eff_firms_delta": -0.02 },
  "pure_truck_factor": { "k": 2 },
  "combined_truck_factor": { "k": 2 },
  "domains": [
    { "domain": "practi.net", "pure_share_pct": 48.04,
      "combined_share_pct": 48.04, "delta_pp": 0.0 },
    ...
  ]
}
```

## Interpreting the delta

| delta_pp | Meaning |
|----------|---------|
| ≈ 0 | Domain's code and review shares are balanced |
| > 0 | Domain is more prominent in review than in code (undercounted by S3) |
| < 0 | Domain is more prominent in code than in review (overcounted by S3) |

| HHI_delta | Meaning |
|-----------|---------|
| < 0 | Combined view is less concentrated = review is more distributed than code |
| > 0 | Combined view is more concentrated = review is even more captured than code |

## Relationship to the footer fix

The pipeline's step 5b (`fix_missing_footers.py`) patches the `cregit.db` footers table
before `generate_dataset.py` produces the Parquet. Without this fix, the footer arrays
in the Parquet would be missing entries whose detection was blocked by BFG repo-cleaner's
blank-line insertion (JGit `getFooterLines` stops at blank lines). The combined knowledge
measure would then undercount review participation for any project processed by BFG.

## Scope notes

- **Token granularity:** the unit of analysis is a single content token (~1 word of code).
  All tokens from the same commit share the same footer arrays. The even split distributes
  credit within each token independently.
- **Domain as firm proxy:** grouping by email domain avoids reliance on external affiliation
  maps. This is coarser than company-level mapping but fully reproducible from the Parquet
  alone. For company-level analysis, pipe through `signals.py`'s gitdm mapping.
- **Even split is conservative:** it *reduces* the author's share when reviewers are present.
  This produces a lower bound on how much S3 over-counts authorship concentration.
- **Scales with review culture:** jq (~2.3% of tokens with footers) shows minimal delta.
  Kernel subsystems (thousands of Reviewed-by/Signed-off-by) will show materially larger deltas.
