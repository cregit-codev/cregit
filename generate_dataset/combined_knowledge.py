#!/usr/bin/env python3
"""
combined_knowledge.py — Measure corporate knowledge concentration integrating
token authorship (S3) with review/footer participation (S4) at token granularity.

For each token in the Parquet dataset, awards 1 "knowledge point" split equally
among the token author (from person_domain) and all footer participants (extracted
from footer_* arrays). Recomputes concentration (HHI, Gini, effective firms,
truck factor) on both the pure-token and combined-knowledge distributions.

SELF-CONTAINED: uses only columns already present in the Parquet dataset.
No external affiliation maps or CSVs required. Grouping is by email domain.

Input:  a cregit Parquet dataset (from generate_dataset.py)
Output: JSON with per-domain pure and combined shares + concentration metrics

Usage:
  python3 generate_dataset/combined_knowledge.py \
      --parquet /path/to/jq-dataset.parquet \
      [--output /path/to/output.json] \
      [--name jq]

Methodology:
  For each token (is_structural=0):
    1. Author domain: person_domain column (already computed from author_email)
    2. Footer domains: extract email from each footer_* array entry via
       regex "<email>", then extract domain from that email
    3. N = |{author_domain} ∪ {footer_domains}|
    4. Award 1/N points to each unique domain
    5. If no footers, author gets 1.0 point (identical to S3 baseline)

  Aggregate to domains → combined_knowledge_share %
  Compare with pure_token_share % (standard S3)

  Concentration metrics (HHI, Gini, effective_firms) computed on both.
"""

import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path


FOOTER_COLS = [
    "footer_signed_off_by",
    "footer_co_authored_by",
    "footer_co_developed_by",
    "footer_reviewed_by",
    "footer_acked_by",
    "footer_tested_by",
    "footer_reported_by",
    "footer_suggested_by",
    "footer_assisted_by",
    "footer_mentored_by",
    "footer_helped_by",
    "footer_based_on_patch_by",
    "footer_thanks_to",
]


def extract_email(text):
    """Extract email from 'Name <email>' format. Returns lowercased email or None."""
    m = re.search(r"<([^>]+)>", text)
    return m.group(1).strip().lower() if m else None


def domain_of(email):
    """Extract domain from email address. Returns '(no-domain)' if missing."""
    return email.lower().split("@", 1)[1] if "@" in email else "(no-domain)"


def gini(values):
    xs = sorted(v for v in values if v > 0)
    n = len(xs)
    if n == 0:
        return 0.0
    s = sum(xs)
    if s == 0:
        return 0.0
    return round(
        (2 * sum((i + 1) * x for i, x in enumerate(xs)) / (n * s)) - (n + 1) / n, 3
    )


def hhi(values):
    s = sum(values)
    if s == 0:
        return 0.0
    return round(sum((v / s) ** 2 for v in values), 3)


def concentration_from_counter(counter):
    """Compute HHI, Gini, effective_firms from a Counter of domain->points."""
    total = sum(counter.values())
    if total == 0:
        return {"HHI": 0, "Gini": 0, "effective_firms": 0, "top1_pct": 0, "holders": 0}
    vals = list(counter.values())
    h = hhi(vals)
    g = gini(vals)
    top1 = max(vals)
    return {
        "HHI": h,
        "Gini": g,
        "effective_firms": round(1 / h, 2) if h else 0,
        "top1_pct": round(100 * top1 / total, 2),
        "holders": len(vals),
    }


def mass_tf(counter):
    """Greedy removal until >50% of mass uncovered. Returns (k, list_of_removed)."""
    s = sum(counter.values())
    removed, order = 0, []
    for domain, count in counter.most_common():
        removed += count
        order.append(domain)
        if removed > 0.5 * s:
            break
    return {"k": len(order), "top_holders": order[:10]}


def main():
    ap = argparse.ArgumentParser(
        description="Combined knowledge share (S3+S4) from cregit Parquet"
    )
    ap.add_argument("--parquet", required=True, help="Path to <name>-dataset.parquet")
    ap.add_argument("--output", help="Output JSON path (default: alongside parquet)")
    ap.add_argument("--name", help="Dataset name (default: inferred from filename)")
    args = ap.parse_args()

    import duckdb

    name = args.name or Path(args.parquet).stem.replace("-dataset", "")
    parquet_path = args.parquet.replace("'", "''")

    con = duckdb.connect()

    # Build footer column concatenation for the query
    footer_concat = " || ".join(f"coalesce({c}, [])" for c in FOOTER_COLS)

    query = f"""
        SELECT
            person_domain AS author_domain,
            {footer_concat} AS all_footers
        FROM read_parquet('{parquet_path}')
        WHERE is_structural = 0
    """

    rows = con.execute(query).fetchall()
    con.close()

    pure_tokens = Counter()  # domain -> token count (pure S3)
    combined_points = Counter()  # domain -> knowledge points (even-split S3+S4)
    total_tokens = 0
    tokens_with_footers = 0

    for author_domain, footer_array in rows:
        total_tokens += 1
        ad = (author_domain or "(no-domain)").strip().lower()
        if not ad:
            ad = "(no-domain)"
        pure_tokens[ad] += 1

        footer_domains = set()
        if footer_array:
            for val in footer_array:
                email = extract_email(val)
                if email:
                    footer_domains.add(domain_of(email))

        all_domains = {ad} | footer_domains
        n = len(all_domains)

        if footer_domains:
            tokens_with_footers += 1

        share = 1.0 / n
        for dom in all_domains:
            combined_points[dom] += share

    pure_total = sum(pure_tokens.values())
    combined_total = sum(combined_points.values())

    # Per-domain comparison table
    all_domains = set(pure_tokens) | set(combined_points)
    domain_table = []
    for dm in sorted(
        all_domains,
        key=lambda d: pure_tokens.get(d, 0) + combined_points.get(d, 0),
        reverse=True,
    ):
        pure = pure_tokens.get(dm, 0)
        comb = round(combined_points.get(dm, 0), 1)
        pure_pct = round(100 * pure / pure_total, 2) if pure_total else 0
        comb_pct = round(100 * comb / combined_total, 2) if combined_total else 0
        delta = round(comb_pct - pure_pct, 2)
        domain_table.append(
            {
                "domain": dm,
                "pure_tokens": pure,
                "pure_share_pct": pure_pct,
                "combined_points": comb,
                "combined_share_pct": comb_pct,
                "delta_pp": delta,
            }
        )

    result = {
        "name": name,
        "total_tokens": total_tokens,
        "tokens_with_footers": tokens_with_footers,
        "pct_tokens_with_footers": round(100 * tokens_with_footers / total_tokens, 2)
        if total_tokens
        else 0,
        "pure_token_concentration": concentration_from_counter(pure_tokens),
        "combined_knowledge_concentration": concentration_from_counter(combined_points),
        "concentration_delta": {
            "HHI_delta": round(
                concentration_from_counter(combined_points)["HHI"]
                - concentration_from_counter(pure_tokens)["HHI"],
                3,
            ),
            "Gini_delta": round(
                concentration_from_counter(combined_points)["Gini"]
                - concentration_from_counter(pure_tokens)["Gini"],
                3,
            ),
            "eff_firms_delta": round(
                concentration_from_counter(combined_points)["effective_firms"]
                - concentration_from_counter(pure_tokens)["effective_firms"],
                2,
            ),
        },
        "pure_truck_factor": mass_tf(pure_tokens),
        "combined_truck_factor": mass_tf(combined_points),
        "domains": domain_table,
    }

    # Determine output path
    if args.output:
        out_path = Path(args.output)
    else:
        out_path = (
            Path(args.parquet).resolve().parent / f"{name}-combined-knowledge.json"
        )

    with open(out_path, "w") as fh:
        json.dump(result, fh, indent=2)
    print(json.dumps(result, indent=2))
    print(f"\nwrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
