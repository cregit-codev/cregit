# Footer columns — analysis queries & research questions

The CreGit Parquet dataset now includes **14 flat `VARCHAR[]` footer columns** per token row:

| Column | Footer key | Research signal |
|---|---|---|
| `footer_signed_off_by` | `signed-off-by` | Review/chain of custody |
| `footer_co_authored_by` | `co-authored-by` | Collaboration |
| `footer_co_developed_by` | `co-developed-by` | AI attribution (RQ5) |
| `footer_reviewed_by` | `reviewed-by` | Peer review |
| `footer_acked_by` | `acked-by` | Acknowledgement |
| `footer_tested_by` | `tested-by` | Testing credit |
| `footer_reported_by` | `reported-by` | Bug reporting |
| `footer_suggested_by` | `suggested-by` | AI attribution (RQ5) |
| `footer_assisted_by` | `assisted-by` | AI attribution (RQ5) |
| `footer_based_on_patch_by` | `based-on-patch-by` | Derived work |
| `footer_helped_by` | `helped-by` | Help credit |
| `footer_mentored_by` | `mentored-by` | Mentorship |
| `footer_thanks_to` | `thanks-to` | Gratitude |
| `footer_personids` | (all resolved) | Resolved person IDs for all footer participants |
| `footer_person_names` | (all resolved) | Resolved display names |

These columns are populated from JGit's `RevCommit.getFooterLines()`, resolved against
the unified persons database. Values are deduplicated and ordered by footer position
within the commit message.

---

## Q1 — Footer prevalence by type

**Research question:** How common is each footer type in this repository?
Which types dominate the collaboration signal?

This directly measures **Signal 4** (mailing-list/review trailers) from the research
proposal. A repo with high `reviewed-by` prevalence suggests a formal review culture;
high `signed-off-by` suggests a DCO-driven process.

```sql
WITH stats AS (
    SELECT count(DISTINCT original_commit_sha) AS total_commits
    FROM dataset
)
SELECT
    f.key AS footer_key,
    count(DISTINCT d.original_commit_sha) AS commits_with,
    s.total_commits,
    round(100.0 * count(DISTINCT d.original_commit_sha) / s.total_commits, 1) AS pct_of_commits
FROM dataset d
CROSS JOIN (
    SELECT unnest(['signed-off-by', 'co-authored-by', 'reviewed-by',
                   'acked-by', 'tested-by', 'reported-by',
                   'co-developed-by', 'assisted-by', 'suggested-by',
                   'based-on-patch-by', 'helped-by', 'mentored-by', 'thanks-to']) AS key
) f
CROSS JOIN stats s
WHERE
    CASE f.key
        WHEN 'signed-off-by'     THEN len(d.footer_signed_off_by) > 0
        WHEN 'co-authored-by'    THEN len(d.footer_co_authored_by) > 0
        WHEN 'reviewed-by'       THEN len(d.footer_reviewed_by) > 0
        WHEN 'acked-by'          THEN len(d.footer_acked_by) > 0
        WHEN 'tested-by'         THEN len(d.footer_tested_by) > 0
        WHEN 'reported-by'       THEN len(d.footer_reported_by) > 0
        WHEN 'co-developed-by'   THEN len(d.footer_co_developed_by) > 0
        WHEN 'assisted-by'       THEN len(d.footer_assisted_by) > 0
        WHEN 'suggested-by'      THEN len(d.footer_suggested_by) > 0
        WHEN 'based-on-patch-by' THEN len(d.footer_based_on_patch_by) > 0
        WHEN 'helped-by'         THEN len(d.footer_helped_by) > 0
        WHEN 'mentored-by'       THEN len(d.footer_mentored_by) > 0
        WHEN 'thanks-to'         THEN len(d.footer_thanks_to) > 0
    END
GROUP BY f.key, s.total_commits
ORDER BY pct_of_commits DESC;
```

If the dataset spans multiple repos, this can reveal cultural differences:
formal DCO repos (Linux) will be `signed-off-by`-heavy; community repos (jq) may be
`co-authored-by`-heavy from PR squash merges.

---

## Q2 — Top footer participants vs. top authors

**Research question:** Who are the most frequently attributed
co-authors/reviewers/testers? How does this rank differ from the author-by-commit
rank?

This identifies **hidden contributors** — people who appear in footers but rarely or
never as commit authors. These are the reviewers, testers, and mentors that
commit-share and DOA (Signals 1–2) miss entirely.

```sql
-- Top footer participants (resolved person IDs)
SELECT
    fpid AS footer_person_id,
    count(*) AS mention_count,
    count(DISTINCT d.original_commit_sha) AS distinct_commits
FROM dataset d,
LATERAL UNNEST(d.footer_personids) AS t(fpid)
WHERE fpid IS NOT NULL
GROUP BY fpid
ORDER BY mention_count DESC
LIMIT 20;
```

```sql
-- Compare with top authors (by token count, a proxy for author activity)
SELECT
    personid,
    person_name,
    count(*) AS token_count,
    count(DISTINCT original_commit_sha) AS commit_count
FROM dataset
WHERE personid IS NOT NULL
GROUP BY personid, person_name
ORDER BY commit_count DESC
LIMIT 20;
```

The gap between these two lists is the most interesting finding. A person who appears
in the top-20 footer list but not the top-20 author list is a **pure reviewer** —
someone whose contribution type would be invisible without the footer columns.

---

## Q3 — Review coverage by source file

**Research question:** Which source files have the highest/lowest ratio of reviewed
commits? Are there files with heavy author churn but no review footers?

This is the **review gap analysis** — finds files where knowledge is concentrated
without external validation.

```sql
SELECT
    file_path,
    count(*) AS total_tokens,
    count(*) FILTER (
        WHERE len(footer_reviewed_by) > 0
           OR len(footer_acked_by) > 0
           OR len(footer_tested_by) > 0
    ) AS reviewed_tokens,
    round(100.0 * count(*) FILTER (
        WHERE len(footer_reviewed_by) > 0
           OR len(footer_acked_by) > 0
           OR len(footer_tested_by) > 0
    ) / count(*), 1) AS review_pct
FROM dataset
GROUP BY file_path
HAVING count(*) > 500  -- only files with enough tokens for statistical relevance
ORDER BY review_pct ASC
LIMIT 30;
```

These files are **knowledge concentration risk candidates**. They have the most
"unreviewed" code, meaning their bus-factor is determined purely by
commit-author-share without the safety net of peer review.

Flipping to `ORDER BY review_pct DESC` shows the most thoroughly reviewed files —
good candidates for examples of the review process working well.

---

## Q4 — Cross-company collaboration via co-authors

**Research question:** What fraction of co-authored commits involve contributors from
different email domains? Which domain pairs collaborate most?

This is the **corporate knowledge flow** question. When author and co-author are
from different domains, knowledge transfers across organizational boundaries.

```sql
WITH coauthor_domains AS (
    SELECT
        d.original_commit_sha,
        d.person_domain AS author_domain,
        list_distinct(
            list_transform(
                d.footer_co_authored_by,
                x -> regexp_extract(x, '@([^>]+)', 1)
            )
        ) AS coauthor_domains
    FROM dataset d
    WHERE len(d.footer_co_authored_by) > 0
      AND d.person_domain != ''
)
SELECT
    author_domain,
    count(DISTINCT original_commit_sha) AS total_coauthored_commits,
    count(DISTINCT original_commit_sha) FILTER (
        WHERE EXISTS (
            SELECT 1
            FROM UNNEST(coauthor_domains) AS t(d)
            WHERE t.d != author_domain AND t.d != ''
        )
    ) AS cross_domain_commits,
    round(100.0 * count(DISTINCT original_commit_sha) FILTER (
        WHERE EXISTS (
            SELECT 1
            FROM UNNEST(coauthor_domains) AS t(d)
            WHERE t.d != author_domain AND t.d != ''
        )
    ) / count(DISTINCT original_commit_sha), 1) AS pct_cross_domain
FROM coauthor_domains
GROUP BY author_domain
HAVING total_coauthored_commits > 5
ORDER BY total_coauthored_commits DESC;
```

Interpreting the results:
- High `pct_cross_domain` for a domain like `@gmail.com` suggests independent
  contributors collaborating across organizations.
- Low `pct_cross_domain` for a corporate domain like `@google.com` where authors
  still have cross-domain co-authors suggests external collaboration.
- A domain with many coauthored commits but 0% cross-domain is a silo.

The same query can be adapted for `footer_reviewed_by` to study **cross-company
review patterns** (reviewers from different companies than the author).

---

## Q5 — AI attribution sensitivity (RQ5)

**Research question:** What fraction of commits carry AI-related footers
(`assisted-by`, `co-developed-by`, `suggested-by`)? How sensitive are the core
results to excluding these commits?

```sql
SELECT
    CASE
        WHEN len(footer_assisted_by) > 0 THEN 'assisted-by'
        WHEN len(footer_co_developed_by) > 0 THEN 'co-developed-by'
        WHEN len(footer_suggested_by) > 0 THEN 'suggested-by'
        WHEN len(footer_assisted_by) > 0
         AND len(footer_co_developed_by) > 0
         AND len(footer_suggested_by) > 0 THEN 'multiple-ai'
        ELSE 'single-ai'
    END AS ai_category,
    count(DISTINCT original_commit_sha) AS commit_count,
    count(*) AS token_count,
    round(100.0 * count(*) / sum(count(*)) OVER (), 2) AS token_pct
FROM dataset
WHERE len(footer_assisted_by) > 0
   OR len(footer_co_developed_by) > 0
   OR len(footer_suggested_by) > 0
GROUP BY ai_category
ORDER BY token_count DESC;
```

```sql
-- Temporal trend: AI footers over time
SELECT
    strftime(author_date, '%Y-%m') AS month,
    count(DISTINCT original_commit_sha) AS total_commits,
    count(DISTINCT original_commit_sha) FILTER (
        WHERE len(footer_assisted_by) > 0
           OR len(footer_co_developed_by) > 0
           OR len(footer_suggested_by) > 0
    ) AS ai_attributed_commits
FROM dataset
GROUP BY month
ORDER BY month;
```

The temporal query reveals whether AI footers are a recent phenomenon in the repo.
To test RQ5 sensitivity, re-run the core corporate-concentration analysis (Signal 4)
with and without the AI footer rows and compare results.

---

## Q6 — Non-trivial review signal (author ≠ reviewer)

**Research question:** In what fraction of reviewed commits is the reviewer a
different person from the author? How many commits have footers where ALL
participants are distinct from the author?

This is the strongest Signal 4 indicator — it detects genuine external review rather
than self-attribution.

```sql
WITH commit_footer_stats AS (
    SELECT
        original_commit_sha,
        personid AS author_personid,
        footer_personids,
        len(footer_reviewed_by) > 0
            OR len(footer_acked_by) > 0
            OR len(footer_tested_by) > 0 AS has_review_footer
    FROM dataset
    WHERE personid IS NOT NULL
)
SELECT
    count(DISTINCT original_commit_sha) AS total_commits,
    count(DISTINCT original_commit_sha) FILTER (
        WHERE has_review_footer
    ) AS commits_with_review_footer,
    count(DISTINCT original_commit_sha) FILTER (
        WHERE has_review_footer
          AND len(footer_personids) > 0
          AND NOT list_contains(footer_personids, author_personid)
    ) AS commits_with_external_reviewer,
    round(100.0 * count(DISTINCT original_commit_sha) FILTER (
        WHERE has_review_footer
          AND len(footer_personids) > 0
          AND NOT list_contains(footer_personids, author_personid)
    ) / nullif(count(DISTINCT original_commit_sha) FILTER (
        WHERE has_review_footer
    ), 0), 1) AS pct_external_of_reviewed
FROM commit_footer_stats;
```

In a healthy project, `pct_external_of_reviewed` should be high — most reviewed
commits should involve someone other than the author. A low value may indicate
pro-forma self-attribution (rubber-stamping).

---

## Q7 — Knowledge dissemination chains (co-author to reviewer flow)

**Research question:** For commits with both co-authors and reviewers, what is the
relationship? Do co-authors also serve as reviewers? Are reviewers from yet another
domain?

This builds a **co-author → reviewer flow** to trace how knowledge moves.

```sql
SELECT
    d.original_commit_sha,
    d.person_domain AS author_domain,
    list_transform(
        d.footer_co_authored_by,
        x -> regexp_extract(x, '@([^>]+)', 1)
    ) AS coauthor_domains,
    list_transform(
        d.footer_reviewed_by,
        x -> regexp_extract(x, '@([^>]+)', 1)
    ) AS reviewer_domains,
    CASE
        WHEN len(d.footer_co_authored_by) > 0
         AND len(d.footer_reviewed_by) > 0
         AND list_has_any(
                list_transform(d.footer_co_authored_by, x -> regexp_extract(x, '@([^>]+)', 1)),
                list_transform(d.footer_reviewed_by, x -> regexp_extract(x, '@([^>]+)', 1))
            )
        THEN 'overlap'
        WHEN len(d.footer_co_authored_by) > 0
         AND len(d.footer_reviewed_by) > 0
        THEN 'distinct'
        WHEN len(d.footer_co_authored_by) > 0 THEN 'coauthor-only'
        WHEN len(d.footer_reviewed_by) > 0 THEN 'review-only'
        ELSE 'none'
    END AS flow_type
FROM dataset d
WHERE len(d.footer_co_authored_by) > 0
   OR len(d.footer_reviewed_by) > 0
GROUP BY d.original_commit_sha, d.person_domain,
         d.footer_co_authored_by, d.footer_reviewed_by
LIMIT 100;
```

Grouping by `flow_type` at the repo level:

```sql
SELECT
    CASE
        WHEN len(footer_co_authored_by) > 0
         AND len(footer_reviewed_by) > 0 THEN 'both'
        WHEN len(footer_co_authored_by) > 0 THEN 'coauthor-only'
        WHEN len(footer_reviewed_by) > 0 THEN 'review-only'
        ELSE 'none'
    END AS collaboration_type,
    count(DISTINCT original_commit_sha) AS commit_count
FROM dataset
GROUP BY collaboration_type
ORDER BY commit_count DESC;
```

This shows whether a repo's collaboration culture is co-author-driven
(Pull Request squash merges) or reviewer-driven (separate review process).

---

## Combining with the persons DB

The Parquet dataset has `footer_personids` as resolved IDs. For richer analysis
(domain, name, person group membership), join back to the persons.db:

```sql
-- This requires DuckDB with sqlite_scanner
CALL sqlite_attach('../cregit-files/jq-persons.db');

SELECT
    fpid AS footer_person_id,
    p.personname,
    e.domain,
    e.emailaddr,
    count(d.*) AS mention_count
FROM dataset d,
LATERAL UNNEST(d.footer_personids) AS t(fpid)
LEFT JOIN persons p ON fpid = p.personid
LEFT JOIN emails e ON fpid = e.personid
WHERE fpid IS NOT NULL
GROUP BY fpid, p.personname, e.domain, e.emailaddr
ORDER BY mention_count DESC
LIMIT 20;
```

---

## Summary: footer columns vs. research signals

| Research proposal theme | Footer columns used | Key insight enabled |
|---|---|---|
| Signal 4 prevalence | All per-key columns | Quantify how common each review/collaboration signal actually is |
| Hidden contributors | `footer_personids` | Identify reviewers who never author commits |
| Review gap analysis | `footer_reviewed_by`, `_acked_by`, `_tested_by` | Find unreviewed files / knowledge concentration risk |
| Cross-company collaboration | `footer_co_authored_by` + `person_domain` | Detect knowledge flow across organizational boundaries |
| AI attribution (RQ5) | `footer_assisted_by`, `_co_developed_by`, `_suggested_by` | Measure sensitivity of results to AI-attributed work |
| Non-trivial review | `footer_personids` + `personid` | Distinguish genuine external review from self-attribution |
| Knowledge dissemination chains | `footer_co_authored_by` + `footer_reviewed_by` | Trace author → co-author → reviewer knowledge flow |
