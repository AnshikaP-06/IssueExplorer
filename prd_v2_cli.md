# Product Requirements Document: Issue Explorer (CLI)

**Author:** Anshika Patel | **Version:** 2.0 (command-line only) | **Date:** October 2026 | **Status:** Ready to build

> **Read this first (AI assistant):** This PRD is the source of truth for *what* to build. A separate file, `build_guide.md`, defines *the order* of work in small steps. Build only the step the user asks for, then stop. Do not add anything listed under "Out of scope."

---

## 1. Product Overview

**Vision:** A command-line tool for Java developers. Given a public GitHub issue URL, it recommends the files and methods in that repository most worth reading first, showing the evidence behind each recommendation and a link to the exact lines at the analyzed commit.

**Problem:** An issue describes behavior but rarely says where it lives in the code. Newcomers spend hours searching folders and guessing class names before they can start investigating.

**What this tool is:** A navigation aid that ranks places to investigate and explains why.

**What this tool is not:** It does not find root causes, generate fixes, run the project's code, or guarantee a file contains the bug. A score is a ranking signal, not a probability.

**Users:** Students, junior developers, and open-source contributors working on public Java repositories.

**Project goals (portfolio):**
1. Show solid Java engineering: parsing, search/ranking, HTTP integration, safe handling of untrusted input, caching, concurrency, tests, CI.
2. Show honest measurement: a held-out evaluation using standard retrieval metrics plus a written failure analysis.
3. Be easy to run and demo from a terminal.

**Non-goals / out of scope for v1:**
- Any frontend or web server, REST API, database, Docker, or cloud deployment
- Languages other than Java
- Private repositories, authentication for end users, GitHub App
- LLM or embedding features, model training
- Running builds or tests of analyzed repositories, generating patches
- Call graphs and relationship views (possible later extension)

---

## 2. Core Scenario

A user runs:

```
issue-explorer analyze https://github.com/owner/repo/issues/123
```

The tool prints progress for each stage, then the top 5 ranked files with, for each: the best-matching method, line range, matched terms, a short code snippet, and a GitHub permalink pinned to the analyzed commit SHA. It also prints a coverage summary (files indexed, skipped, failed). Running it again for the same repository and commit reuses the cached download and index.

---

## 3. Feature Requirements

Priority: **M**ust, **S**hould, **C**ould, **W**on't (v1).

| ID | Feature | Description | Priority | Acceptance criteria |
|----|---------|-------------|----------|---------------------|
| F1 | Issue URL parsing | Parse `https://github.com/owner/repo/issues/N` | M | Valid URLs yield owner, repo, number; PR URLs, other hosts, malformed or extra-junk input rejected with a specific message |
| F2 | Issue retrieval | Fetch title and body via GitHub REST API | M | 404, rate limit, network failure, and pull-request results handled with clear errors |
| F3 | Commit resolution | Resolve `--ref` (branch/tag/SHA) or the default branch to a full commit SHA | M | 40-char SHA printed; unknown ref and empty repo produce clear errors |
| F4 | Snapshot download | Download repository archive at the SHA into a local cache | M | Size limit enforced; cached snapshots are reused and reported as such |
| F5 | Safe extraction and filtering | Extract archive safely and keep only relevant `.java` files | M | Zip-slip entries rejected, symlinks skipped, file-count and size limits enforced, build/vendor directories skipped, counts reported |
| F6 | Java parsing | Extract package, imports, types, methods (name, signature, line range, Javadoc) with JavaParser | M | Fixture repo counts match exactly; unparseable files counted as failed and never crash the run |
| F7 | Identifier tokenization | Split camelCase/PascalCase/snake_case/digits, keep the original identifier too | M | `createRecurringBooking` is findable as "create recurring booking" and as the exact name |
| F8 | Search index | Lucene index, one document per method, stored in the cache | M | Index keyed by repo + SHA + index version; reused if present; rebuilt if missing |
| F9 | Issue hint extraction | Extract class names, `.java` filenames, and stack-trace frames from issue text | M | Unit-tested against fixture and real-issue text |
| F10 | Ranking | BM25 over weighted fields, boosts for hints, methods aggregated into files | M | Fixture issues return expected files in top 5 |
| F11 | Evidence output | For each result: path, method, line range, matched terms, snippet, commit-pinned permalink | M | Permalinks open the correct lines; snippet lines match the range |
| F12 | `analyze` command | Full pipeline with progress output, coverage summary, exit codes | M | Integration test passes using a mock GitHub server and fixture archive |
| F13 | JSON output | `--json` for machine-readable results | S | Output validates against a documented shape |
| F14 | Parallel parsing | Parse files with a bounded thread pool | S | Results identical to sequential parsing; per-stage timing printed |
| F15 | Configuration | Limits and defaults in a config file; env var `GITHUB_TOKEN` optional | S | Documented defaults; flags override config |
| F16 | Evaluation harness | `evaluate <dataset.json>` computing Hit@5, Recall@5, MRR for dev and held-out splits | M | Metric math unit-tested against hand-computed examples |
| F17 | Weak-match labelling | Mark results with low scores as "low confidence" | C | No-match fixture issue shows low-confidence wording |
| F18 | Test/source filter | `--exclude-tests` to hide test files | C | Filter works without re-indexing |
| F19 | Relationship view | Imports/inheritance neighborhood | W | Deferred |
| F20 | REST API / Spring Boot wrapper, Docker, saved history | | W | Deferred; possible later extensions |

---

## 4. Commands

| Command | Purpose |
|---------|---------|
| `hello` | Smoke test for the CLI (may be removed later) |
| `parse-url <url>` | Print owner, repo, issue number |
| `show-issue <url> [--ref R]` | Print issue title/body and resolved commit SHA |
| `download <url> [--ref R]` | Download and cache the snapshot, then extract and filter |
| `parse-dir <path>` | Parse a local directory and print declaration counts |
| `index <path>` | Build or reuse a Lucene index for a local directory |
| `rank --issue-file F --index P` | Rank files for an issue text file against an index |
| `analyze <issue-url> [--ref R] [--top N] [--json] [--exclude-tests]` | Full pipeline |
| `evaluate <dataset.json>` | Run the evaluation and print metrics |

**Exit codes:** `0` success; `1` internal error; `2` invalid input or usage; `3` not found or not public; `4` rate limited; `5` size/count limit exceeded.

Errors print one clear line to stderr (no stack traces by default; a `--verbose` flag may show more) and never print the token.

---

## 5. Technical Design

**Stack:** Java 21, Maven, picocli, `java.net.http.HttpClient`, Jackson, JavaParser, Apache Lucene, JUnit 5, Mockito, a local mock HTTP server for tests (WireMock or the JDK's `HttpServer`), GitHub Actions.

**Style:** A single Maven module with clear packages. No frameworks beyond the libraries above.

```
com.anshika.issueexplorer
  cli/          picocli commands, exit codes, output formatting
  github/       URL parsing, GitHub client, rate-limit handling
  snapshot/     download, cache layout, safe extraction, filtering, limits
  parsing/      JavaParser wrappers, declaration model
  indexing/     tokenizer, Lucene writer/reader, index versioning
  ranking/      issue hint extraction, query building, scoring, aggregation
  evaluation/   dataset model, metrics, report
  config/       defaults, config file, env handling
```

### 5.1 Cache layout (no database)
```
~/.issue-explorer/
  config.properties            (optional)
  cache/<owner>/<repo>/<sha>/
    archive.zip
    src/                       (extracted .java files only)
    index-v<N>/                (Lucene index)
    meta.json                  (commit SHA, file counts, parser version, index version, timestamps)
```
Cache key = repository + commit SHA + parser version + index version. Changing the issue text never invalidates the source index.

### 5.2 Snapshot retrieval
- Resolve the ref to a full SHA first and record it.
- Download the repository **archive (zipball) at that SHA in one request** instead of fetching files individually. **Assumption to verify:** check GitHub's documentation on how archive downloads count toward rate limits and any size constraints.
- Safe extraction: reject entries whose normalized path escapes the target directory, skip symlinks, enforce limits while extracting (not afterward).
- Keep only `.java` files. Skip directories such as `target/`, `build/`, `.git/`, `node_modules/`, and obvious generated output.

### 5.3 Parsing and indexing
- JavaParser extracts per file: package, imports, types, methods (name, signature, start/end line, Javadoc/comments).
- Lucene document per method. Fields: `identifier` (exact and split tokens), `path`, `comments`, `body`, `kind` (source/test), plus stored fields for evidence (file path, method name, signature, start/end line).
- Snippets are read from the extracted source file using the stored line range.

### 5.4 Ranking
1. Extract hints from the issue: class names, `.java` filenames, stack-trace frames.
2. Query Lucene with BM25 over weighted fields (identifier and path weighted above comments and body).
3. Boost exact matches for the extracted hints.
4. Rank methods, then aggregate into files (maximum method score plus a small bonus for other top methods). Make weights configurable.
5. Tune weights on the development set only. The held-out set is used once, for reporting.

### 5.5 Concurrency
Parse files with a bounded `ExecutorService` (default 4 threads, configurable). Results must be identical to sequential parsing (deterministic ordering).

### 5.6 Configuration defaults (initial values, adjust after measuring)

| Setting | Default |
|---------|---------|
| Max archive download | 50 MB |
| Max Java files kept | 5,000 |
| Max total extracted size | 200 MB |
| Parser threads | 4 |
| Top results | 5 |
| GitHub token | read from `GITHUB_TOKEN` if set |

---

## 6. Non-Functional Requirements

### Security and untrusted input
- Build GitHub API URLs only from validated owner, repo, and issue number. Do not follow redirects to other hosts.
- Verify the repository is public. If a token is used, it must never be printed, logged, or committed.
- **Never execute** code from analyzed repositories. Parse only.
- Safe archive extraction (path traversal, symlinks, size/count bombs).
- Do not log source contents. Secrets come from environment variables or untracked config only. Provide `.gitignore` entries and a `.env.example` if needed.
- Respect licensing: cached sources stay on the user's machine; do not redistribute analyzed code or datasets without checking licenses.

### Reliability and usability
- Network failures, rate limits, partial coverage, and parse failures are reported clearly and never crash the program with a stack trace.
- Interrupted downloads or indexes must not leave a half-built cache entry that looks valid (write to a temp location, then move).
- Output is readable in a plain terminal; `--json` output is stable and documented.

### Performance
Report cold-run time and cached-run time separately, per stage. Set numeric targets only after baselines are measured. No performance claim goes into the README or resume before it is measured.

---

## 7. GitHub API Budget

- Unauthenticated: roughly 60 requests/hour per IP; authenticated: generally 5,000/hour. Read the rate-limit response headers rather than hard-coding limits.
- Per `analyze` run, aim for: one issue request, one ref-resolution request, one archive download.
- Cache by commit SHA. On rate limiting, stop and print when to retry.

---

## 8. Testing and Evaluation

### 8.1 Fixture repository
A small hand-built Java project (about 15 classes across 3 areas such as booking, accounts, reporting, with Javadoc and some test classes) in `src/test/resources/fixture-repo`, with fixture issues and expected relevant files in JSON: one naming a class explicitly, one with a stack trace, one vague, one with no match. Fixtures validate behavior only; they do not prove real-world accuracy.

### 8.2 Engineering tests
- **Unit:** URL parsing (many valid/invalid cases), tokenizer (including acronyms like `HTTPServer` and digits), hint extraction, declaration extraction, ranking, permalink building, metric math.
- **Security:** a maliciously crafted zip with `../` entries; oversized archive; symlink entries.
- **Mocked GitHub:** 404, pull-request result, rate limit with headers, timeout, truncated body, unknown ref.
- **Integration:** `analyze` end to end against a mock GitHub server and a prebuilt fixture archive.
- **Determinism:** parallel and sequential parsing produce identical results.

### 8.3 Evaluation on real repositories
- Choose 3-5 public Java repositories with clear issue-to-fix links. Check whether an existing Java bug-localization dataset is reusable and appropriately licensed.
- Target 30-50 issues. For each: analyze the commit **before** the fix, use only the issue text as written before resolution, and exclude later comments that reveal the answer.
- Ground truth: files changed by the fix, manually checked (exclude formatting-only and docs-only changes).
- Split roughly 70% development / 30% held-out. Tune on development only.

**Dataset format**
```json
[
  {
    "issueUrl": "https://github.com/owner/repo/issues/123",
    "commitSha": "abc123...",
    "relevantFiles": ["src/main/java/com/x/Foo.java"],
    "split": "dev"
  }
]
```

| Metric | Definition |
|--------|------------|
| Hit@5 | Share of issues with at least one relevant file in the top 5 |
| Recall@5 | Share of labeled relevant files found in the top 5 |
| MRR | Mean reciprocal rank of the first relevant result |

Also report cold and cached timing and coverage. Write a failure analysis that groups misses by cause (for example: business-language issue with no identifiers, huge file, parse failure).

---

## 9. Success Criteria

- All Must features pass their acceptance criteria and tests in CI.
- A held-out evaluation report exists with Hit@5, Recall@5, MRR, and timing, plus limitations.
- A README with usage examples, architecture, trade-offs, limitations, and a short terminal demo recording.
- No numbers are claimed anywhere until measured.

---

## 10. Release Plan (milestones are gates)

Estimates are for part-time vibe coding and should be revised after Milestone 1.

| # | Milestone | Build guide steps | Gate | Rough estimate |
|---|-----------|-------------------|------|----------------|
| 1 | Foundation and GitHub access | 0-5 | Issue and commit SHA retrieved for a real public issue; tests green | 4-6 days |
| 2 | Snapshot handling | 6-7 | Repo downloaded, safely extracted, filtered, cached | 3-5 days |
| 3 | Parse, index, rank | 8-14 | Fixture issues return expected files with evidence | 1-1.5 weeks |
| 4 | Full command and hardening | 15-16 | `analyze` works end to end; integration test passes | 4-6 days |
| 5 | Evaluation | 17-18 | Held-out metrics and failure analysis written | 5-8 days (manual labeling dominates) |
| 6 | CI, README, demo | 19 | Green CI, README, demo recording | 2-3 days |

**You can list the project on your resume once Milestone 4 works**, then update the bullets with measured numbers after Milestone 5.

---

## 11. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Scope creep | Project stalls | One step at a time; the Won't list is binding |
| Lexical search weak on vague issues | Low hit rate | Report honestly, categorize failures, note semantic search as future work |
| GitHub rate limits | Runs fail | Single archive download, caching by SHA, token support, clear retry message |
| Large repositories | Slow or failed runs | Hard limits, partial-coverage reporting |
| JavaParser fails on some syntax | Missing declarations | Count failures, continue |
| Noisy ground truth | Misleading metrics | Manual checks; document that fix-changed files are a proxy |
| AI-generated code the author can't explain | Interview risk | Read and run every step; ask the AI to explain design choices |

---

## 12. Open Questions and Assumptions

**Open questions**
1. How does the archive download count toward GitHub rate limits? (Verify in docs before step 6.)
2. Which 3-5 public Java repositories and which existing bug-localization dataset (if any) should be used?
3. Max vs. weighted-sum aggregation of method scores into file scores (decide using the development set).

**Assumptions**
- v1 supports public Java repositories of bounded size.
- Issue comments are excluded to keep evaluation clean.
- The tool runs on the author's machine; there is no server component.

---

## 13. Rules for the AI Coding Assistant

1. Work on **one step at a time** from `build_guide.md`, then stop and list files changed plus the commands to verify.
2. Do not add features, dependencies, frameworks, or files that the current step doesn't need. **Ask before adding any dependency** outside the stack in section 5.
3. Write tests with every change. Run `mvn test` and report the result.
4. Keep the package layout in section 5. Prefer small, readable classes.
5. Never hard-code secrets; never print or log the GitHub token.
6. Never execute analyzed code. Treat all issue text and repository contents as untrusted.
7. Make limits configurable rather than hard-coded.
8. Explain non-obvious design choices briefly so the author can describe them in interviews.
9. Do not claim performance or accuracy numbers anywhere until the evaluation produces them.
10. If a requirement here is unclear or conflicts with a step, ask instead of guessing.
