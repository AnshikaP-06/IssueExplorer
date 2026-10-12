# IssueExplorer — Step 16

Java 21 Maven project: `com.anshika:issue-explorer:0.1.0-SNAPSHOT`.
The picocli root command is `issue-explorer`; its `hello` subcommand prints
`Hello from issue-explorer`. Tests verify test discovery and command output.

Dependencies are pinned in `pom.xml`: picocli 4.7.7, Jackson databind 3.2.3,
JavaParser 3.28.2, Lucene core/analysis-common/queryparser 10.5.2,
JUnit Jupiter 5.14.4 and Mockito 5.24.0. Stable versions were checked against
Maven Central on October 10, 2026. Jackson 3 uses `tools.jackson.core` coordinates.
JUnit remains on the requested 5.x release line.

Maven 3.9.11 and Git are already installed. A portable, checksum-verified
Temurin Java 21 JDK is in `.tools/`. Maven uses the ignored project-local `.m2/`
cache through `.mvn/maven.config`.

Run in PowerShell:

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
java -version
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar hello
```

`use-java21.ps1` enables Java 21 only for the current PowerShell session.
Expected: Tests run: 448, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS.
The JAR prints `Hello from issue-explorer`.

The files under `setup/` are historical dependency preparation files.
Use the root `pom.xml` for all project builds.

The Maven Shade plugin bundles runtime dependencies and sets the CLI main class.
Service providers are merged for libraries such as Lucene; test dependencies are excluded.


## Parse a GitHub issue URL

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar parse-url https://github.com/anshika/issue-explorer/issues/123
```

Expected:

```text
Owner: anshika
Repo: issue-explorer
Issue number: 123
```

Only HTTPS URLs on github.com with exactly `/owner/repo/issues/number` are accepted.
The number must be positive and fit a Java long. Host names are case insensitive;
owner/repo spelling is preserved, and leading zeros in issue numbers are normalized.
Queries, fragments, credentials, explicit ports, encoded path segments, trailing
slashes and extra path segments are rejected. Pull request URLs are rejected.
Invalid URL input prints a specific error on stderr and exits with code 2.

The parser has 10 valid and 30 invalid parameterized examples; CLI tests check
output, error handling, missing arguments and extra arguments. No dependencies
were added in this step.

## Fetch an issue

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar show-issue https://github.com/owner/repo/issues/123
```

Replace owner, repo, and 123 with a public issue. This command contacts GitHub.
It prints `Title: ...`, followed by `Body:` and the issue body. A null body is empty.
Set an optional `GITHUB_TOKEN` in the environment before running; no token is
required for public issues. Tokens are sent only in the Authorization header and
are never included in error messages. No token or credentials are stored by the app.

The client uses the JDK HttpClient and Jackson already in the project. Redirects
are disabled, with 30-second connection/request timeouts. API URLs are built only
from the validated issue owner, repository, and number.

Errors print one line to stderr. Exit codes: 1 for network, timeout, invalid JSON,
authentication, and other API failures; 2 for invalid input or a pull request;
3 for HTTP 404; 4 for rate limiting. The client stops rather than retrying automatically.
Retry-After is preferred; a primary rate limit uses X-RateLimit-Reset when
X-RateLimit-Remaining is zero. Retry times are printed in UTC. Missing/invalid
rate-limit timing headers use an explicitly labeled 60-second fallback.

Tests use a loopback JDK HttpServer and an injected clock/API base URL. They cover
request path and headers, optional authentication, multiline and null bodies,
404, primary and secondary rate limits, malformed headers/responses, unexpected
statuses, rejected redirects, connection failure, timeout, interruption,
URL validation, pull requests, and command output/exit codes. No tests call GitHub.
No dependencies were added in Step 4.

References: [GitHub rate-limit handling](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
and [REST authentication](https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api).

## Resolve a commit (Step 5)

`show-issue` now prints `Commit SHA: <40-character SHA>` after the title and body.
Both retrieval and ref resolution must succeed before anything is printed to stdout.

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar show-issue https://github.com/owner/repo/issues/123
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar show-issue https://github.com/owner/repo/issues/123 --ref main
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar show-issue https://github.com/owner/repo/issues/123 --ref v1.0.0
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar show-issue https://github.com/owner/repo/issues/123 --ref feature/fix
```

Replace the URL and refs with actual public repository values for manual usage.
An omitted ref uses `GET /repos/{owner}/{repo}/commits?per_page=1`, which defaults
to the repository's default branch. An explicit branch, tag, or full/short commit
uses `GET /repos/{owner}/{repo}/commits/{ref}`. Refs are encoded as a single path
segment, including slashes. No default branch name is hard-coded. Resolution
requires one API request after fetching the issue.

Unknown ref/not-public responses (404) and empty repositories (409 or an empty
commit list) return code 3. Ref validation failures (422) return code 2. Malformed
commit responses or non-full SHAs return code 1. Existing network, authentication,
redirect, timeout, and rate-limit handling is shared between issue and commit requests.
Returned SHAs are validated as 40 hexadecimal characters and normalized to lowercase.

Step 5 adds no dependencies. All 140 tests pass using local mocked HTTP responses;
no automated tests contact GitHub. The runnable JAR was rebuilt and --ref help verified.

Reference: [GitHub commits API](https://docs.github.com/en/rest/commits/commits).

## Download a repository archive (Step 6)

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar download https://github.com/refinedev/refine/issues/7607
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar download https://github.com/refinedev/refine/issues/7607 --max-download-bytes 100000000
```

The command validates and retrieves the issue, rejects pull requests, checks that
the repository is public, resolves the commit, and caches `archive.zip` at:

```text
~/.issue-explorer/cache/<owner>/<repo>/<sha>/archive.zip
```

`~` means the Java user.home directory (normally C:\Users\anshi on this machine).
Owner/repo names and commit SHAs are normalized to lowercase in cache paths.
Optional flags: `--ref <branch/tag/commit>`, `--cache-dir <cache-root>`,
`--max-download-bytes <positive-byte-count>`. Default limit is 50,000,000 bytes
(decimal 50 MB). Limit errors return exit code 5 and one clear stderr message.

Both declared Content-Length and streamed bytes are checked. Chunked responses
are bounded too; bytes exceeding the configured limit are never written to disk.
Downloads use unique .part files, which are removed after success or failure.
ZIP metadata is checked before publishing archive.zip. A valid existing archive
within the current size limit produces `Already cached; skipped download:` and
skips the archive request. Issue, visibility, and ref requests still run to obtain
the current SHA. Corrupt cached archives are downloaded again. Nothing is extracted.

GitHub's zipball API returns a redirect, so a cold archive download normally
requires an API request plus a transfer from codeload.github.com. The client
allows one redirect only to that exact HTTPS host, sends no Authorization header
on the second request, and rejects other hosts and redirect chains. A same-origin
loopback redirect is permitted for mock server tests. JSON endpoint redirects
remain disabled. API rate-limit headers are used rather than assuming a fixed
budget; the docs do not provide a universal ZIP archive-size limit. The 50 MB
limit is enforced locally by IssueExplorer.

All 182 tests pass against local mock HTTP servers with temporary cache folders.
No dependencies were added. The runnable JAR was rebuilt and download help verified.

Reference: [GitHub ZIP archive endpoint](https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip).

## Safe extraction (Step 7)

`download` now extracts retained Java source files into the `src/` folder beside
`archive.zip`. The common archive root folder is removed so source paths remain
relative to the repository. The extraction runs again when the archive is cached,
so filtering and limits are applied on every invocation.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar download https://github.com/refinedev/refine/issues/7607
```

Optional extraction limits: `--max-files 5000` and
`--max-extracted-bytes 200000000` (200 decimal MB). These limits apply to retained
Java files and actual bytes written, not discarded entries. Exceeding either
returns exit code 5. Unsafe ZIP paths return exit code 2. Invalid ZIP metadata,
checksum mismatch and filesystem errors return exit code 1.

Only files ending in `.java` are retained. Directories named target, build, .git,
node_modules, generated, generated-sources and generated-test-sources are excluded
at any depth. Symlink entries and their descendants are skipped. Directory records
are ignored, except symbolic-link directory records count as skipped symlinks.
Output reports kept Java files, skipped entries grouped by reason, extracted bytes,
and the source directory. Source/test Java files are both retained.

Every entry path is validated before filtering: traversal, absolute paths,
backslashes, drive/alternate-stream syntax, and unsafe Windows names are rejected.
Extraction takes place in a fresh temporary directory, does not follow archive
symlinks, checks actual streamed size and CRC32, and publishes src only after success.
Failed extraction removes staging files and preserves any previously published src.

No dependencies were added. Java 21 does not expose ZIP external attributes, so
ZipEntryModes reads central-directory Unix mode bits to identify symlinks. This
bounded implementation supports ordinary single-volume ZIP archives; ZIP64 and
split archives are rejected explicitly rather than extracted without full checks.
Format reference: [PKWARE ZIP specification](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).

Verification: all 218 tests pass, including malicious ../ paths, symlink entries,
excluded directories, file/size limits, false declared sizes, CRC mismatch, cleanup,
replacement and preservation of existing sources. The runnable JAR was rebuilt.
The existing Refine cache was checked without network requests: 0 Java files kept;
11,521 skipped (1 symlink, 1 excluded-directory file, 11,519 non-Java files).

## Fixture project and synthetic issues (Step 8)

`src/test/resources/fixture-repo` is a standalone Java 21 Maven project with
12 production classes and three JUnit test classes (15 classes total). Each of
booking, accounts, and reporting has four production classes and one test class.
Class and method Javadoc describe cancellation rules, weekly reservations,
account lockout, decimal revenue totals, and spreadsheet cell formatting.

`src/test/resources/fixture-issues` contains four issue text files and
`expected-relevant-files.json`. Each JSON record has id, category, issueFile,
and relevantFiles. Issue filenames are relative to fixture-issues; expected Java
paths are relative to fixture-repo. Categories are explicit-class, stack-trace,
vague, and no-match. The no-match record has an empty relevantFiles array.
These are synthetic investigation labels, not assertions of real defects or
measured ranking accuracy. The stack trace names an actual fixture source line.

Verification commands, run from the IssueExplorer folder after enabling Java 21:

```powershell
mvn test
mvn -f .\src\test\resources\fixture-repo\pom.xml "-Dmaven.repo.local=D:\Projects\IssueExplorer\.m2" test
```

The IssueExplorer suite passes 222 tests. The fixture's own Maven project passes
8 tests. These owned fixture sources are safe to compile for verification;
IssueExplorer still never executes code from downloaded repositories.
No application or ranking code, root POM changes, or new dependencies were added.
The fixture reuses the JUnit version and Maven plugin versions already present.


## Parse local Java declarations (Step 9)

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar parse-dir .\src\test\resources\fixture-repo
```

Expected summary: `Java files: 15; parsed: 15; failed: 0`.
Fixture totals: 28 imports, 15 types, 33 methods. Tests assert per-file counts exactly.

JavaDeclarations stores package name, imports (including static/wildcard flags),
types (name and class/interface/enum/record/annotation kind), and methods (name,
full declaration signature, one-based inclusive start/end lines, plain Javadoc text).
Default-package files use an empty package name; CLI output displays `(default)`.
Nested declarations are included. Constructors, annotation members, inherited
methods and implicit record accessors are not counted as MethodDeclaration nodes.
Absent Javadoc is an empty string. No symbol solving or repository code execution occurs.

parse-dir prints package/import/type/method counts for every parsed file. Invalid
syntax, unreadable files, and parser failures produce a FAILED entry and are counted;
other files continue. Partial ASTs from unsuccessful parses are discarded. Completion
with per-file failures returns 0 with the coverage summary. Invalid directory/usage
returns 2; directory traversal/read failure returns 1.

Files are processed sequentially and output paths are sorted. Filesystem symlinks
are not followed. Build/vendor/generated directories excluded by snapshot extraction
are also skipped during local directory traversal.

Maven test-resource configuration now excludes nested **/target/** folders. This
prevents the fixture project's compiled test classes from being discovered as
IssueExplorer test classes after running the fixture separately.

Verification: 249 tests passed after a clean build. The runnable JAR was rebuilt
and parse-dir verified against the fixture. No dependencies or ranking code added.

## Identifier tokenizer (Step 10)

IdentifierTokenizer.tokenize returns an immutable list of distinct lowercase
words in first-seen order, followed by the complete lowercase identifier when
it is not already present. It splits camelCase, PascalCase, acronym-to-word
boundaries, snake_case, dollar separators, and letter/digit boundaries.
Consecutive digits remain one token. Unicode letters, combining marks and
Unicode decimal digits are supported. Lowercasing uses Locale.ROOT.

Examples:
- createRecurringBooking -> create, recurring, booking, createrecurringbooking
- HTTPServer -> http, server, httpserver
- HTTP2Server -> http, 2, server, http2server
- create_booking -> create, booking, create_booking

Original identifier tokens preserve underscores/dollar signs and digits;
only their casing changes. Repeated words are deduplicated. Blank input yields
an empty list. Null input produces a clear IllegalArgumentException.
No CLI command, index, ranking code, or dependencies were added.

Verify in PowerShell after enabling Java 21:

```powershell
mvn test
mvn "-Dtest=IdentifierTokenizerTest" test
```

All 290 tests passed, including 41 tokenizer cases covering acronym boundaries,
number sequences, repeated separators/words, Unicode, locale independence,
immutability, deterministic calls, null and blank input.

## Cached Lucene method index (Step 11)

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar index .\src\test\resources\fixture-repo
```

Expected: Built index, Methods indexed: 33, Java files parsed: 15; failed: 0.
Run the last command again to see Already indexed; reused cache.

One Lucene document is written per parsed method. Indexed fields are identifier,
path, comments (Javadoc plus contained comments), body, and kind (source/test).
Stored evidence includes method name, signature, and inclusive start/end lines.
The identifier field uses IdentifierAnalyzer, which applies the Step 10 tokenizer
at both index and query time. Other text fields use StandardAnalyzer. No files
are executed, and source contents are never printed by the command.

Cache layout: ~/.issue-explorer/cache/<owner>/<repo>/<sha>/index-v1/.
Lucene commit metadata stores repo, SHA, index/parser versions and coverage counts.
Only a readable committed index with matching metadata is reused. Missing or
incomplete indexes are rebuilt in temporary directories and published after success.

For extracted snapshots ending in <owner>/<repo>/<40-character-SHA>/src, repo and
SHA are inferred. You can supply explicit identity with both --repo owner/repo
and --sha <full-SHA>. These describe an immutable snapshot; do not reuse that
identity for changed sources. --cache-dir overrides the cache root.

Ordinary local folders use repo local/<folder-name> and a 40-character SHA-1
content key derived from their absolute path and Java source contents. This is a
local cache key, not a Git commit SHA. Changing local source produces a new key.
Malformed files are excluded from documents and retained in failure counts.

Tests verify exactly 33 fixture documents (25 source, 8 test), required fields,
method bodies/comments, failed-file handling, local content changes, index version
and SHA separation, cache reuse, incomplete-cache rebuilding, and direct test-folder
classification. Searches for createRecurring and create recurring both return
BookingService.createRecurringBooking first. Search is exercised in tests; there
is no search/ranking CLI yet. No dependencies were added.

Verification: all 306 tests passed. Use the normal mvn test command, or:

```powershell
mvn "-Dtest=MethodIndexTest,IndexCommandTest" test
```

## Issue hint extraction (Step 12)

IssueAnalyzer.analyze(title, body) returns immutable IssueHints containing
classNames, javaFiles, and stackFrames. Lists preserve spelling/case and deduplicate
hints in first-seen order within each category. Null title/body are treated as empty.

Class candidates begin with uppercase and have both lowercase letters and at least
two uppercase letters (BookingService, HTTPServer, ObjectMapper). Ordinary sentence
capitalization, all-capitals acronyms, and lowerCamel method names are excluded.
Explicit Java filenames and stack frames also supply class hints, including simpler
names such as Account. Filenames are basenames extracted from paths and Markdown links;
.java.bak and other incorrect suffixes are rejected.

Each frame stores the qualified class, method, source filename and positive line.
Module/classloader prefixes and nested classes are supported. Native Method/Unknown
Source retain the class/method with null filename and line. Invalid or overflowing
line numbers are ignored. Repeated frames are deduplicated; distinct line numbers remain.

Fenced backtick/tilde and indented code blocks containing only unrelated words are
ignored. Blocks with actual Java filenames or valid Java frames are retained regardless
of fence language. Java/unlabelled snippets are also retained when they contain Java
syntax cues such as class declarations, imports, or static-style method calls.
Other language blocks without Java evidence are ignored. This is a lexical heuristic,
not a guarantee that every CamelCase word names a real class in the target repository.
Issue text is data and is never executed or interpreted as instructions.

All four fixture issues and two minimal, attributed Jackson issue excerpts are tested.
The excerpts live in src/test/resources/real-issue-excerpts; their README identifies
original URLs and the small formatting adaptations. Tests run offline, with no network.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn "-Dtest=IssueAnalyzerTest" test
```

Verification: 345 tests passed, including 39 analyzer cases. No dependencies,
new CLI commands, scoring, or file-ranking behavior were added in this step.

## BM25 file ranking (Step 13)

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar index .\src\test\resources\fixture-repo
```

Copy the index path printed above into the following variable. For the current
fixture on this machine, the path is:

```powershell
$indexPath = 'C:\Users\anshi\.issue-explorer\cache\local\fixture-repo\93d0e77d4a743430747fd579992c257d9d65ac9c\index-v2'
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar rank --issue-file .\src\test\resources\fixture-issues\explicit-class.txt --index $indexPath
```

Change explicit-class.txt to stack-trace.txt, vague.txt or no-match.txt to try the
other fixtures. The no-match case prints No matching files. Results print up to
five repository-relative file paths with ranking scores. Scores are ranking
signals, not probabilities and should not be compared across different issues.

Default configurable flags:

| Flag | Default |
| --- | --- |
| --identifier-weight | 4 |
| --path-weight | 3 |
| --comments-weight | 2 |
| --body-weight | 1 |
| --class-hint-weight | 8 |
| --file-hint-weight | 10 |
| --stack-hint-weight | 12 |
| --method-bonus | 0.1 |

Weights must be finite and non-negative; at least one text-field weight must be
positive. Method bonus must lie between 0 and 1. A zero hint weight disables that
hint boost. For example, append --identifier-weight 5 --method-bonus 0.05.

IssueRanker builds literal Lucene term queries with BM25Similarity. Identifier
splitting is applied to issue identifiers; common English stop words and generic
issue headings are omitted. Text queries use up to 128 distinct terms and require
up to two distinct term groups, avoiding results supported by only one incidental
term in longer issues. This is an initial lexical precision heuristic, not a
measured confidence threshold. Exact class/filename matches and filename+method
matches from stack frames are boosted separately using keyword fields. All matching
methods are collected before file aggregation. File score = best method score +
method-bonus times the sum of the next two best method scores. Files tie-break by
path; methods by start line and signature. Test files remain eligible.

Index version is now 2, adding typeExact, fileExact and methodExact fields. The
index command builds index-v2 without reusing an incompatible index-v1. rank
rejects old versions with a clear rebuild instruction. No dependencies were added.

Verification: all 368 tests pass. Every labeled relevant fixture file appears in
the top five; the no-match fixture returns no files. Tests also verify exact hints,
camel/split queries, configurable field weighting, aggregation math, stable ordering,
input validation, and CLI output. The runnable JAR was rebuilt, index-v2 created,
and all four fixtures ranked successfully. This validates fixture behavior only;
no real-world accuracy or performance is claimed.

## Ranked source evidence and JSON (Step 14)

Rebuild indexes with the index command: the schema is now index-v3, adding the
source root, snapshot/local identity flag and per-file SHA-256 fingerprints.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar index .\src\test\resources\fixture-repo
$indexPath = 'C:\Users\anshi\.issue-explorer\cache\local\fixture-repo\93d0e77d4a743430747fd579992c257d9d65ac9c\index-v3'
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar rank --issue-file .\src\test\resources\fixture-issues\explicit-class.txt --index $indexPath
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar rank --issue-file .\src\test\resources\fixture-issues\explicit-class.txt --index $indexPath --json
```

Each result shows the file, best method name, full method line range, matched terms,
a source snippet and a permalink. Matched terms are query terms verified against
the method document's actual indexed postings, including exact hint fields.
They are not guessed from the snippet alone; matches may occur in other indexed fields.

The snippet contains at most eight actual source lines beginning at the method's
start, never extending past its end. It has its own start/end line range and numbered
lines; the permalink covers the full method. Source fingerprints must still match
the index. Changed or missing source files cause a clear error instead of stale
snippets. For local source changes, run index again and use the new content-key path.
For modified downloaded snapshots, restore the sources from the commit-pinned cached
archive using download before ranking. Indexes remain tied to their original source
location; moving the source causes a rebuild when running index with the new location.

Snapshot permalinks use https://github.com/owner/repo/blob/<40-character-SHA>/<path>#Lstart-Lend.
Path segments are URL encoded. Branch names and local content hashes are never used
as fake commit links. A normal local-folder index prints an unavailable explanation;
its JSON repository, commitSha and permalink values are null. Downloaded snapshot
folders infer repository/SHA, or index can accept explicit --repo owner/repo --sha SHA
for sources that genuinely correspond to that commit. Synthetic fixture test URLs
are checked for formatting only and are not claimed to exist on GitHub.

JSON shape (schemaVersion 1):

```json
{
  "schemaVersion": 1,
  "repository": "owner/repo",
  "commitSha": "0123456789abcdef0123456789abcdef01234567",
  "results": [
    {
      "path": "src/Example.java",
      "score": 3.5,
      "method": "create",
      "signature": "public void create()",
      "startLine": 42,
      "endLine": 51,
      "matchedTerms": ["create"],
      "snippet": {
        "startLine": 42,
        "endLine": 43,
        "lines": [{"number": 42, "text": "public void create() {"}, {"number": 43, "text": "}"}]
      },
      "permalink": "https://github.com/owner/repo/blob/0123456789abcdef0123456789abcdef01234567/src/Example.java#L42-L51"
    }
  ]
}
```

This is a structural example, not a real ranking result. No-match JSON has results: [].
JSON stdout contains only the report. Errors and optional Lucene runtime diagnostics
remain on stderr. Scores are ranking signals, not confidence probabilities.

Verification: 389 tests passed, including permalink encoding/ranges, numbered and
bounded snippets, stale-source detection, matched terms, JSON structure, and local
identity handling. The runnable JAR, human evidence, and JSON parsing were verified.
No dependencies were added.

## Full analyze pipeline (Step 15)

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar analyze --help
```

Manual example for a public Java repository (this contacts GitHub):

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --top 5
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --top 3 --json
```

Optional --ref accepts a branch, tag or SHA; omission resolves the default branch.
The resolved full SHA pins downloads, cache entries and every result permalink.
--top must be positive. GITHUB_TOKEN remains optional and is read from the environment.

Stages: validate URL; fetch issue; verify public repository and resolve commit;
download/cache snapshot; safely extract/filter; parse; build/reuse index; rank;
prepare evidence. Progress and a coverage message go to stderr. Successful human
stdout prints title, commit, ranked evidence and coverage. JSON stdout contains only
one report; errors use a single Error: line on stderr with no stack trace or token.

Cold runs normally make four GitHub API requests (issue, repository visibility,
commit resolution, zipball), plus the trusted archive redirect transfer. Cached
runs still retrieve issue/visibility/ref, but skip the archive transfer and reuse
index-v3 when valid. Extraction and parsing run on each invocation to refresh the
source tree and coverage. Parsed declarations are passed to MethodIndex, avoiding
a second parse during index construction.

Additional flags retain existing configurable safeguards: --cache-dir,
--max-download-bytes (50,000,000), --max-files (5,000),
--max-extracted-bytes (200,000,000). Ranking uses the current default weights;
standalone rank continues to provide the weight override flags.

Coverage counts:
- filesIndexed: successfully parsed Java files with at least one method document.
- filesSkipped: filtered archive entries plus Java files with no method declarations.
- filesFailed: Java parse/read failures, counted without stopping other files.
- methodsIndexed: total method documents.
- skippedByReason: symlinks, excluded directories, non-Java files, and optionally
  Java files without methods. Ordinary directory records do not count as files.

Analyze JSON schemaVersion 1 has issueUrl, title, repository, commitSha,
archiveCached, indexCached, coverage and results. Each result uses the Step 14
path/score/method/signature/startLine/endLine/matchedTerms/snippet/permalink shape.
A no-match report has results: []; partial parsing still returns successful coverage.

Exit codes: 0 completed (including no matches or partial parsing); 1 network,
filesystem or internal error; 2 invalid usage/input, pull request or unsafe ZIP;
3 issue/repository/ref unavailable, private repository or empty repository;
4 rate limited, with retry timing; 5 download/extraction size or count limit exceeded.
Failure leaves stdout empty; incomplete downloads/indexes/source trees are not published.

The checked-in fixture-archive.zip contains owned fixture Java sources plus filtering
and parse-failure cases. Its README documents exact coverage (15 indexed, 4 skipped,
1 failed, 33 methods). AnalyzeIntegrationTest serves it using the local mock GitHub
server and temporary caches. It verifies the entire pipeline, snippets/permalinks,
all nine progress stages, both cache hits with identical results, ref/top options,
JSON separation, visibility, pull requests, invalid input, not-found/rate/network
errors, size/count limits, malicious traversal, cache-write errors and no-method files.
No tests call GitHub or execute the analyzed source. No dependencies were added.

Verification: all 414 tests passed, including 25 integration cases. The runnable JAR
was rebuilt and analyze help checked. Run just the integration cases with:

```powershell
mvn "-Dtest=AnalyzeIntegrationTest" test
```

## Parallel parsing, configuration and timings (Step 16)

Defaults now come from src/main/resources/issue-explorer-defaults.properties:

```properties
max.download.bytes=50000000
max.java.files=5000
max.extracted.bytes=200000000
parser.threads=4
top.results=5
```

Edit config.example.properties and use --config .\config.example.properties to load
it without rebuilding. By default commands read ~/.issue-explorer/config.properties
when present; otherwise bundled defaults apply. An explicit missing file is an error.
Partial property files inherit missing defaults. Precedence: explicit CLI flags,
then property file, then bundled defaults. Unknown properties, non-numeric values,
non-positive values and overflow are rejected. Tokens remain environment-only.

analyze supports --threads and --config along with existing top/limit flags.
parse-dir and index support --threads and --config. download uses configured
archive/extraction limits; rank uses configured top.results, with --top override.
Local parsing also enforces configured Java file-count and total-source-byte limits.
Limit violations return exit code 5. All byte settings use decimal bytes, not MiB.

Parallel parsing uses a ThreadPoolExecutor with a bounded ArrayBlockingQueue.
At most the configured number of tasks are in flight. Each task owns its JavaParser;
results are sorted by relative path before indexing. --threads 1 selects sequential
parsing. Worker counts are capped at the number of input files; empty folders require
no workers. Executor shutdown occurs on success, failure and interruption.

analyze prints monotonic elapsed timings for validate-url, fetch-issue,
resolve-commit (including public visibility), download, extract, parse, index,
rank and evidence, plus total. Progress/timings stay on stderr; --json stdout stays
parseable and includes timingsMs with numeric millisecond values. Failed stages also
print elapsed timing. Cold/cached flags remain in the report; no speedup is claimed.
parse-dir prints its parsing duration on stderr.

Rate-limit errors include an explicit UTC retry timestamp and wait duration.
When both primary reset and secondary Retry-After apply, the later valid time is
used. Missing/malformed timing headers use a labeled 60-second fallback; past reset
times receive a minimum one-second wait. Fractional waits round upward. No automatic
retry or blocking sleep is added, and tokens never appear in these messages.

Verify:

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar parse-dir .\src\test\resources\fixture-repo --config .\config.example.properties --threads 1
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar parse-dir .\src\test\resources\fixture-repo --config .\config.example.properties --threads 4
mvn "-Dtest=ParallelParsingTest,AppConfigTest,StageTimingsTest,AnalyzeIntegrationTest" test
```

Both parse commands print identical declaration counts and the summary:
Java files: 15; parsed: 15; failed: 0. Their elapsed timings may differ.

Real public Java issue example (contacts GitHub):

```powershell
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar analyze https://github.com/FasterXML/jackson-databind/issues/4499 --config .\config.example.properties --threads 4 --top 5 --json
```

Verification: 448 tests passed, including 9 parallel parsing cases, 16 config cases,
2 timing cases, and 29 integration cases. Fixture declarations, mixed parse failures,
full pipeline results and coverage are exactly equal across sequential/parallel runs.
Packaged one-thread/four-thread parsing was compared and matched. No dependencies added.

Files created/changed for Step 16:
- config/AppConfig.java and config/AppConfigTest.java
- src/main/resources/issue-explorer-defaults.properties and config.example.properties
- parsing/JavaDirectoryParser.java, ParsingLimitException.java and ParallelParsingTest.java
- analysis/StageTimings.java, StageTimingsTest.java, AnalyzePipeline.java and AnalyzeIntegrationTest.java
- cli/AnalyzeCommand.java, DownloadCommand.java, ParseDirCommand.java, IndexCommand.java and RankCommand.java
- cli/ParseDirCommandTest.java
- snapshot/SnapshotDownloader.java and SnapshotExtractor.java
- github/GitHubClient.java and GitHubClientTest.java
- SETUP.md

## Step 17: File ranking evaluation

Run `evaluate <dataset.json>` with a JSON array. Every entry has `issueUrl`, a full
40-character `commitSha`, repository-relative `relevantFiles` (forward slashes),
and `split` (`dev` or `heldout`). Example shape (replace the example URL and SHA):

```json
[
  {
    "issueUrl": "https://github.com/owner/repo/issues/123",
    "commitSha": "0123456789abcdef0123456789abcdef01234567",
    "relevantFiles": ["src/main/java/example/BookingService.java"],
    "split": "dev"
  }
]
```

Evaluation fetches each issue and analyzes the repository at its pinned SHA using
existing caches, defaults and weights. Issue text is fetched as it exists today;
this dataset does not freeze historical issue text. No tuning is performed on
heldout entries. Invalid datasets and duplicate issue/commit pairs are rejected
before analysis. Failures stop evaluation without printing a complete summary.

Per-issue Hit@5 is 1 if any labeled file appears in the first five predictions,
otherwise 0. Recall@5 is the number of distinct labeled files in the first five
divided by the total number of distinct labeled files. MRR uses the reciprocal
rank of the first relevant file across all returned matches, not just five.
No matching prediction scores zero. Empty relevantFiles scores zero for all
metrics; this is not a separate no-match accuracy metric. Split scores are macro
averages (each issue has equal weight); empty splits print N/A. Paths match exactly,
including case. Duplicate predictions/labels do not inflate scores.

Hand check used in EvaluationTest and EvaluateCommandTest:

| Issue | Predictions | Relevant | Hit@5 | Recall@5 | Reciprocal rank |
|---|---|---|---|---|---|
| 1 | a, x, b | a, b | 1 | 1 | 1 |
| 2 | x, c, y, z, q, d | c, d | 1 | 1/2 | 1/2 |
| 3 | x | e | 0 | 0 | 0 |

All three in dev give Hit@5 = 2/3, Recall@5 = 1/2, MRR = 1/2.
Tests also cover separate splits, rank-five/six boundaries, invalid input, empty
labels/results, duplicates, pipeline failures and a mocked GitHub fixture zip.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn "-Dtest=EvaluationTest,EvaluateCommandTest" test
mvn test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar evaluate --help
# After creating your labeled dataset with actual issue URLs and commit SHAs:
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar evaluate .\dataset.json
# Optional configuration and cache location:
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar evaluate .\dataset.json --config .\config.example.properties --cache-dir .\.evaluation-cache
```

Files added: evaluation/Evaluation.java, cli/EvaluateCommand.java,
evaluation/EvaluationTest.java, cli/EvaluateCommandTest.java.
Files changed: cli/IssueExplorerCommand.java and SETUP.md. No dependencies added.

## Terminal readability update

Human-readable rank/analyze recommendations now have a blank line and separator
between files, plus blank lines before snippets and permalinks. Evaluation prints
one predicted file per line and a blank line between issue cases. JSON output is
unchanged. No dependencies added.

Changed: cli/EvidencePrinter.java, cli/EvaluateCommand.java,
cli/EvaluateCommandTest.java, SETUP.md. Added: cli/EvidencePrinterTest.java.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn "-Dtest=EvidencePrinterTest,EvaluateCommandTest,RankCommandTest,AnalyzeIntegrationTest" test
mvn package
```

Rerun your previous analyze/rank/evaluate command to see the updated spacing.

## Step 19: GitHub Actions and README

Added `.github/workflows/ci.yml`: every push and pull request runs
`mvn --batch-mode --no-transfer-progress test` on ubuntu-latest with Temurin Java
21. The workflow uses read-only contents permissions and no application secrets.
Added README.md with installation, runnable jar commands, illustrative output,
configuration, architecture, design choices, limitations, evaluation methodology,
metric definitions and TBD result placeholders. Evaluation weight-tuning support
is documented as a current gap. No dependencies or application code changed.

```powershell
Set-Location 'D:\Projects\IssueExplorer'
. .\use-java21.ps1
mvn --batch-mode --no-transfer-progress test
mvn package
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar hello
java -jar .\target\issue-explorer-0.1.0-SNAPSHOT.jar evaluate --help
```

The local test command verifies the existing application tests. Remote GitHub
Actions execution is not confirmed locally; after pushing the workflow to a
GitHub repository, open its Actions tab and confirm the Java 21 tests run.
