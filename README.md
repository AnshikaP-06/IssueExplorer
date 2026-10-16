# IssueExplorer

IssueExplorer is a Java 21 command-line tool (`issue-explorer`) that helps you decide
which Java files and methods to read first when investigating a public GitHub
issue. It ranks files using the issue text and returns method names, source line
ranges, matched terms, short snippets, and links pinned to the analyzed commit.
Recommendations are starting points for investigation, not confirmed bug locations.

## Install and run

Requirements: a Java 21 JDK and Maven 3.9 or later available on PATH. Install the
JDK and Maven using their official instructions:
[Temurin](https://adoptium.net/installation/) and
[Apache Maven](https://maven.apache.org/install.html).
They do not need to be installed inside the project folder.

Clone or download this repository, open a terminal in its root, then run:

```sh
java -version
mvn -version
mvn test
mvn package
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar hello
```

Output of the last command:

```text
Hello from issue-explorer
```

The packaged jar includes its dependencies. On the original Windows workspace:

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar --help
```

`use-java21.ps1` selects the locally downloaded JDK under `.tools/`. That folder
is ignored by Git and is not present in fresh clones; use your installed JDK in
a fresh clone. Maven downloads dependencies into the project-local `.m2/` folder
as configured in `.mvn/maven.config`. The first build needs internet access.

## Analyze an issue

```sh
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --top 5
```

Without `--ref`, this analyzes the default branch's current commit. Use a branch,
tag, or full commit SHA to select a different snapshot:

```sh
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --ref YOUR_FULL_COMMIT_SHA --top 5
```

Replace `YOUR_FULL_COMMIT_SHA` before running. For historical evaluation, choose
the commit before the fix. Default-branch results do not measure historical accuracy.

Illustrative output layout below uses placeholders, not measured rankings or
reported evaluation results. Actual methods, scores, timings and paths vary:

```text
Issue: <issue title>
Commit SHA: <resolved full commit SHA>

------------------------------------------------------------
1. src/main/java/example/BookingService.java (score=<score>)
   Method: createRecurringBooking (lines <start>-<end>)
   Matched terms: booking, recurring

   Snippet (lines <start>-<snippet-end>):
     <line> | <source code from the selected method>

   Permalink: https://github.com/<owner>/<repo>/blob/<SHA>/<path>#L<start>-L<end>

------------------------------------------------------------
2. <another suggested file> (score=<score>)
   Method: <method name> (lines <start>-<end>)
   ...

Coverage: files indexed=<count>, skipped=<count>, failed=<count>; methods indexed=<count>
```

Progress, stage timings, cache-hit messages and errors go to stderr. Human results
or JSON go to stdout. Add `--json` to capture machine-readable results:

```sh
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --json > report.json
```

An optional `GITHUB_TOKEN` environment variable authenticates requests. Set it in
your shell or secret manager; never put tokens in source files or configuration.
The client reports rate limits with a UTC retry time; retry manually afterward.

## Commands and configuration

| Command | Purpose |
|---|---|
| `hello` | Installation smoke check |
| `parse-url <url>` | Validate and split a GitHub issue URL |
| `show-issue <url> [--ref <ref>]` | Fetch title/body and resolve a commit |
| `download <url> [--ref <ref>]` | Download a cached snapshot and safely extract Java files |
| `parse-dir <path> [--threads <N>]` | Print declaration counts and parse failures |
| `index <path>` | Build or reuse a local method index; prints its location |
| `rank --issue-file <file> --index <path>` | Rank files from an existing index |
| `analyze <url> [--ref <ref>] [--top <N>] [--json]` | Run the whole pipeline |
| `evaluate <dataset.json>` | Print per-issue and split-level ranking metrics |

Use `<command> --help` for available options. Paths containing spaces need quoting.

Defaults come from `src/main/resources/issue-explorer-defaults.properties`.
Optional `~/.issue-explorer/config.properties` overrides them; `--config <file>`
selects a different file. Supported CLI overrides take precedence.
Copy and edit `config.example.properties` for a starting configuration:

| Property | Default |
|---|---:|
| `max.download.bytes` | 50000000 |
| `max.java.files` | 5000 |
| `max.extracted.bytes` | 200000000 |
| `parser.threads` | 4 |
| `top.results` | 5 |

Byte limits use decimal MB. Unknown properties and invalid values are rejected.
Four parser threads means up to four Java files are parsed concurrently.

## Architecture and design decisions

```text
Issue URL -> GitHub issue + resolved SHA -> cached zipball
         -> safe Java extraction -> JavaParser (bounded workers)
         -> Lucene method index -> issue hints + BM25 ranking
         -> file aggregation -> source evidence -> terminal / JSON
```

- `cli`: picocli commands and shared terminal formatting.
- `github`: strict issue URL parsing and REST requests with Java HttpClient/Jackson.
- `snapshot`: size-limited downloads and safe extraction without executing sources.
- `parsing`: JavaParser declarations, source ranges, Javadoc and bounded parallel parsing.
- `indexing`: identifier tokenization and one Lucene document per method.
- `ranking`: issue hints, configurable BM25 field weights, file scores and evidence.
- `analysis`, `config`, `evaluation`: orchestration, limits and file-level metrics.

Identifier splitting preserves both components and the original lowercase identifier.
Identifier/path fields have higher default weights than comments/body. Exact class,
filename and stack-frame hints receive boosts. File scores use the best method
plus a small bonus from up to two other matching methods. These are heuristics.

Snapshots live under `~/.issue-explorer/cache/<owner>/<repo>/<sha>/`, with
`archive.zip`, extracted `src/`, and a versioned `index-v3/`. Repeat analysis skips
a valid cached download and reuses a matching index; extraction and parsing still
run to refresh sources and coverage. Local indexes use a source-content identity;
GitHub permalinks are unavailable unless a real repository/commit identity exists.

The tool has no frontend, Spring, database or Docker requirement. It does not
compile, run tests from, or execute code in downloaded repositories.

## Limitations

- Public GitHub issue URLs only; PR URLs and PR objects are rejected. Java source
  repositories are required for useful recommendations.
- Parses Java 21 syntax without symbol resolution or call-graph analysis. Syntax
  failures are counted and processing continues; review the coverage summary.
- Constructors, implicit record methods and annotation members are not indexed
  as methods. Java files with no declared methods cannot receive method scores.
- Test methods can appear in results; the index records source/test status but
  ranking does not currently apply a test penalty.
- Extraction skips symlinks, non-Java files, and build/generated/dependency folders.
  Paths escaping the destination are rejected. ZIP64 and split archives are unsupported.
- Large repositories may exceed configured limits. Partial parsing coverage can
  reduce ranking quality; file paths and line ranges must refer to the same snapshot.
- Issue hint extraction is heuristic. No match does not establish that no bug exists.
- Local source changes invalidate stored evidence; rebuild a local index. For a
  pinned GitHub snapshot, restore the source snapshot from its cached archive.
- Issues are fetched as they exist today. Post-fix edits can leak solution hints
  into historical evaluation; the current dataset format does not freeze issue text.
- No real-dataset performance results have been reported yet.

Exit codes: `0` completed (including no matches/partial parsing), `1` I/O/network
or internal failure, `2` invalid input/PR/unsafe archive, `3` unavailable issue,
repository or ref, `4` rate limit, `5` resource limit.

## Evaluation

Create a JSON array; replace all placeholders with real verified labels:

```json
[
  {
    "issueUrl": "https://github.com/OWNER/REPO/issues/NUMBER",
    "commitSha": "REPLACE_WITH_FULL_40_CHARACTER_PRE_FIX_SHA",
    "relevantFiles": ["src/main/java/example/RelevantClass.java"],
    "split": "dev"
  }
]
```

For each issue, inspect its linked fix and record the commit before the behavior
was fixed. Label production Java files containing behavioral fixes; consistently
exclude docs, formatting-only changes, unrelated changes and regression tests.
Use repository-relative forward-slash paths present in the pre-fix snapshot.
Keep fix links and labeling notes separately for auditability.

Collect 30-50 issues across 3-5 repositories and freeze approximately a 70/30
split. Keep closely related fixes together. Existing datasets such as
[Defects4J](https://github.com/rjust/defects4j) and
[Bench4BL](https://github.com/exatoa/Bench4BL) may help, but require verified
GitHub issue URLs, upstream commits and file-path adaptation.

Keep dev and heldout entries in separate JSON files: evaluation runs every entry
in its supplied file, even though the averages are reported by split.

```sh
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar evaluate dataset-dev.json
# After freezing the settings, run heldout once for reported numbers:
java -jar target/issue-explorer-0.1.0-SNAPSHOT.jar evaluate dataset-heldout.json
```

`evaluate` uses default ranking weights. `rank` accepts weight flags, but those
flags are not yet connected to `evaluate`/`analyze`. End-to-end weight tuning
requires that extension before comparing settings on dev. Do not tune on heldout.

Metrics are macro-averaged per issue, separately for dev and heldout:

- **Hit@5:** 1 if a relevant file is among the first five predictions, else 0.
- **Recall@5:** relevant files in the first five divided by all labeled relevant files.
- **MRR:** reciprocal rank of the first relevant file across all returned matches.

Duplicate labels/predictions do not inflate scores. Unmatched issues and empty
relevant-file lists score zero; empty splits print N/A. File paths match exactly,
including case. Any processing failure stops evaluation without a complete summary.


## Tests and CI

```sh
mvn test
mvn "-Dtest=EvaluationTest,EvaluateCommandTest" test
```

Metric tests include a three-issue hand calculation. Pipeline integration tests
use a local mock GitHub HTTP server and a prebuilt fixture zip, not real GitHub.
The owned fixture project and issues are under `src/test/resources/fixture-repo`
and `src/test/resources/fixture-issues`.

`.github/workflows/ci.yml` runs `mvn test` with Temurin Java 21 on every push and
pull request. It needs no personal GitHub token. A remote CI run can be confirmed
in the repository's Actions tab after these files are pushed to GitHub.
See [SETUP.md](SETUP.md) for the development history and additional checks.
