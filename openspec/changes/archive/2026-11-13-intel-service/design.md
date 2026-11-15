# Design

## Context

See proposal.md for motivation and specs/vulnerability-intel/spec.md for required behavior. This is a new,
standalone service with no code yet. It does not depend on the `copilot-mordernize-harness` Maven modules (that harness
is Java 21 / Spring Boot 3.5); the two only meet later through NATS and files, so their versions are independent.

Observed shape of the feed (fetched 2026-11): RSS 2.0 with `content:encoded` HTML, about 7 items at a time, an item
`guid` that includes a date (for example `sshole@2026-11-05`), `pubDate` at midnight UTC, CVE ids in the text, tags as
`<category>` elements, and fixed `<h2>` sections (Updates, Details, Risk, Affected deployments, Recommended action,
Cloudflare coverage, Evidence and sources). "Last reviewed" appears only inside the content as a `<time>` element.

## Goals / Non-Goals

**Goals:**
- One small deployable (single Spring Boot application) that ingests daily and serves the MCP tool.
- One embedded database kept in a local file, using standard SQL only so a later move to a server database stays cheap.
- Stable pagination and strict, testable safety rules for fetching and parsing external content.

**Non-Goals:**
- No auditing, NATS publishing, or fixing logic (separate changes).
- No write operations over MCP, no user accounts, no UI beyond the JobRunr dashboard.
- No semantic or full-text search; filters are exact or simple text matches.

## Decisions

### D1. Stack: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, JobRunr 9.0.3 (all GA)
Versions are pinned through the Spring Boot Gradle plugin and the Spring AI BOM; no milestone or release-candidate
artifacts. Spring AI 2.1 and Boot 4.2 are release candidates only at the time of writing and are excluded.
The build is Gradle (Kotlin DSL, Gradle wrapper) with a Java 25 toolchain, so the compiler JDK does not depend on
whichever JDK happens to run Gradle.
*Alternatives:* Java 21 / Boot 3.5 (matches the harness, but the harness is not a dependency, and the user chose the
newer stack); Spring AI 2.1 RC (newer MCP features, but not GA for a service serving security data); Maven (the
harness uses it, but Gradle was chosen for this service).
The first implementation task builds an empty skeleton on this exact set to prove the combination resolves and starts.

### D2. MCP server via Spring AI's MCP server starter, web (servlet) variant, HTTP transport
The tool is a plain annotated method on a Spring bean; the starter publishes it. Only that one tool is registered, and
no resources or prompts that mutate state. The server uses the **streamable-HTTP** transport (Spring AI 2.0.2 with MCP
Java SDK 2.1.0, endpoint `/mcp`), confirmed on 2026-11-10 against the MCP SDK client in tests and against the Claude CLI
2.1.322 (`type: "http"`), which listed the tool, called it with every kind of argument and read the error text. The
server advertises only the `tools` capability (plus logging) and a single tool marked read-only and idempotent. The
Copilot CLI was not installed on the development machine (only the VS Code shim that offers to install it), so it is
untested. Known framework behaviour: a tool error is returned as the exception message followed by the root cause's
message, so for our argument errors the sentence appears twice in the one error text; it still names the argument.
*Alternatives:* stdio (cannot be shared by other applications, rejected); a hand-written REST API plus a separate MCP
facade (more code, two contracts to keep in sync).

### D3. Access protection: localhost bind by default, optional bearer token
The server binds to `127.0.0.1` unless configured otherwise. If a token is configured, a servlet filter in front of the
MCP endpoint and of the actuator endpoints requires `Authorization: Bearer <token>` using a constant-time comparison,
and rejects before any tool logic runs. The two orchestrator probes (`/actuator/health/liveness` and `/readiness`)
stay open, matched by exact path: they carry no details, and a container platform has no secret to send. A non-loopback
bind without a token refuses to start. The token and the dashboard's credentials are separate secrets and neither
opens the other surface.
*Alternatives:* Spring Security with OAuth2 resource server (heavier than needed for a service-to-service read-only
tool; can replace the filter later without changing the tool).

### D4. Storage: embedded H2 database in a local file
One embedded H2 database, opened in file mode from a configurable data directory, holds both the vulnerability data and
JobRunr's job state through one shared data source. There is no database server to run, and tests and production use the
same engine. The schema is managed by Flyway migrations and written in standard SQL only: CVE ids, tags and evidence
links live in child tables (not array or JSON columns), the raw payload is `TEXT`, keyset pagination uses plain
comparisons, and no engine-specific features are used, so moving to PostgreSQL later means changing the driver and the
JDBC URL rather than the schema. The database is opened in embedded mode, so only one process can open the data file at
a time; a second instance fails to start instead of corrupting data, and this is the documented single-instance rule.
Backups are a copy of the data directory while the service is stopped.
*Alternatives:* PostgreSQL in production with SQLite for local runs (the earlier decision; two engines and a container
for tests); PostgreSQL only (server to operate for roughly a handful of rows a day); H2 in server mode (allows several
processes, but adds a listening port to secure and is not needed for one service).
Tables: `vulnerability` (one row per item, with identity `source_name` + `source_item_id`, fingerprint, timestamps,
section texts, raw payload, immutable `sort_at`), `vulnerability_cve`, `vulnerability_tag`, `vulnerability_link`,
`feed_source_state` (validators, last attempt, last success, last error) and `ingestion_run` (one row per run with
counts and outcome).

### D5. Ingestion job: JobRunr recurring job, transactional per run
A recurring job with a configurable cron expression and time zone runs the ingestion. Operations use JobRunr's own
dashboard instead of a hand-written operations API: it already shows jobs by state (scheduled, enqueued, processing,
succeeded, failed), failure details and retries, recurring jobs and their next run, and the background servers, and its
"Trigger now" on the recurring job is the manual trigger. Every ingestion run is also recorded in `ingestion_run` with
its counts and failure reason, and each job writes one line to its own dashboard log (run id, outcome, and the counts or
the failure reason) so "what did this run process?" is answered next to the job's state. A hand-written API for the
same questions was rejected as duplicated work to maintain.
The dashboard is a different kind of surface from the MCP endpoint, so it is configured separately and defensively.
It is off unless explicitly enabled. JobRunr's dashboard server binds to every network interface and has no setting to
choose an address, and it speaks plain HTTP, so the service refuses to start with the dashboard enabled unless a username
and a password of at least 12 characters are set, and those credentials are never logged. The credentials are the
dashboard's own basic authentication (`jobrunr.dashboard.enabled`, `.username`, `.password`, `.port`). Because basic
authentication over plain HTTP is not enough on an untrusted network, deployments place a TLS-terminating reverse proxy
or a tunnel in front of it and restrict the port by network rules. A "Trigger now" run is recorded as a scheduled run,
since the dashboard runs the recurring job itself.
A run fetches and fully parses the feed first, then writes all item changes in one transaction, so a bad or oversized
response never leaves partial data. Failures are recorded in `ingestion_run` and rethrown so JobRunr's retry and
dashboard show them.
The recurring job is registered under a fixed id, so a restart updates the stored schedule instead of adding one; the
first scheduled run is the next cron time after startup, not startup itself. JobRunr's open-source edition does not
catch up on runs missed during downtime (that is a Pro feature), so startup adds a catch-up: if no ingestion has ever
succeeded or the last success is older than 24 hours, the same job is enqueued once immediately. A run interrupted by a
shutdown can be retried by JobRunr after restart; this is safe because ingestion is idempotent (fingerprint upsert in a
single transaction).

### D6. Fetching: standard HTTP client, manual redirects, hard limits
`java.net.http.HttpClient` with redirects disabled; the code follows at most a few redirects itself, re-validating each
target: HTTPS only, host on the configured allowlist, and the resolved address not loopback, private, link-local or
a metadata-service address. Responses are read through a bounded stream (default 5 MiB) with connect and total timeouts
and an expected XML content type. Requests send `If-None-Match` and `If-Modified-Since` when validators were stored, and
a descriptive `User-Agent`. The origin was checked on 2026-11-10: it sends neither `ETag` nor `Last-Modified` (only
`Cache-Control: public, max-age=300`) and ignores conditional requests, so in practice every run downloads the whole
feed (about 65 KB) and the per-item fingerprints decide what changed. Validator support costs nothing and takes
effect if the origin ever adds them.

### D7. Parsing: typed RSS model bound by Jackson XML; a small guard; HTML sections via an HTML parser
The RSS response is described by Java types that mirror the wire format (`RssDocument`, `RssChannel`, `RssItem`,
`RssGuid`, records in `dev.intel.feed.rss`) and bound by Jackson XML (Jackson 3, managed by Boot); no code walks DOM
nodes by hand. Normalization then maps these wire types to the stored draft, so parsing and interpretation stay separate.
A small guard (`FeedGuard`) runs first with the JDK's SAX parser: it rejects any DOCTYPE (which also rules out every
external or recursive entity, in every encoding), rejects malformed XML and enforces a maximum element depth, element
count and item count before anything is built; the binder's own parser additionally has DTDs and external entities
off. The binder is not used on the whole channel because it silently drops items that are not contiguous (another
element between two items, or a namespaced item). Instead a tokenizer (`ItemSpans`) finds the exact text of every item,
skipping comments, processing instructions and CDATA, and each item is bound on its own inside the document's
`<rss><channel>` opening so that namespace prefixes resolve. The guard's item count must equal the tokenizer's, otherwise
the feed is rejected rather than losing items silently. The raw payload of each item is its text exactly as received
(entities, CDATA and attribute quoting untouched).
`content:encoded` is parsed with an HTML parser into plain text, split on `<h2>` headings into the known sections; text
before the first heading is the overview (status line and introduction); unknown headings are kept under an "other"
bucket and the raw payload is always stored, so a change in the feed's layout loses structure but not data. Links
are taken from the "Evidence and sources" section only (absolute http or https), which excludes the product links of the
"Cloudflare coverage" section.
Observed feed shape (fixture, 8 items): `<category>` mixes tags, a status label ("Needs attention" or "Reference") and CVE
ids, so category values that are CVE ids join the CVE list and the rest are tags; one item has no CVE ids and one has no
"Cloudflare coverage" section. CVE ids are extracted with a pattern over title, description, content and categories.
"Last reviewed" comes from the `<time>` element next to that label and is null when absent. The content fingerprint is a
SHA-256 over the whitespace-normalized title, description, content and sorted categories.

### D7a. Stable ordering and cursors
Results are ordered by `sort_at` descending, then id, where `sort_at` is the published time (or first-seen time when
the item has none) set once at insert and never updated. A cursor encodes the last `(sort_at, id)` plus a hash of the
query, so continuation is a keyset query that cannot skip or repeat earlier records when items are corrected, and a
cursor from a different query is rejected. The `recent` filter and sort are separate: the filter checks published or
last-reviewed time against the window.

### D8. Tool response shape
A response is `{ records, nextCursor, freshness }`. `freshness` carries `lastSuccessfulIngestionAt`, `stale` (default
threshold 36 hours) and a status of `ok`, `stale` or `never-ingested`. Records carry identity, title, source URL, the
timestamps (published, last reviewed, first seen, last updated), CVE ids, tags, the section texts and evidence links,
but not the raw payload. Every response states that record text is untrusted external content, so agents that consume
it are reminded not to follow instructions in it. The product filter matches tags exactly (case-insensitive) or
the title and affected-deployments text by parameterized substring match.

### D9. Operations
The `ingestion` health component is `UP` while the data is fresh (even if a later run failed and is being retried, since
the service still answers correctly) and `DOWN` when the data is stale or no ingestion ever succeeded, with the last run,
its counts and its failure reason as details; the tool and the indicator share one freshness calculation so they cannot
disagree. Micrometer counters `intel.ingestion.runs{outcome}` (`SUCCESS`, `NO_CHANGE`, `FAILED`, `REFUSED`) and
`intel.ingestion.items{result}` (`new`, `updated`, `unchanged`, `skipped`) are registered at zero and published with
JobRunr's own job and server metrics through the metrics endpoint. Logs carry the run id, counts and the stable failure
reason and never feed text: skipped items are logged by position only, and the few values an origin controls (redirect
targets, host and element names, content types) are reduced to printable ASCII and capped. Configuration (feed URL,
allowlist, cron, time zone, size and time limits, staleness threshold, bind address, token, data directory) comes from
environment-overridable properties.

### D10. Testing approach
- Parser and normalizer tests run against a saved copy of the real feed as a fixture, plus hand-built cases for items
  without CVEs, missing sections, and corrected items.
- Hostile-input tests: an XML document with an external entity or DOCTYPE, an oversized body, redirects to HTTP, to a
  foreign host and to a private address.
- Repository tests run on H2 (in memory for speed), plus one file-mode test that ingests, restarts the application and
  finds its data, and one that checks a second process cannot open the same data file.
- MCP tests connect with an MCP client over HTTP and check the advertised tool list contains only the read tool,
  authorized and unauthorized calls, filters, validation errors, pagination under concurrent correction, and freshness
  states.

## Risks / Trade-offs

- [Spring AI 2.0.2 may build against a different Boot minor than 4.1.2] → Task 1 builds the skeleton on the pinned set;
  if the combination fails, pin the Boot version Spring AI documents and record it here.
- [JDK 25 (Microsoft build 25.0.5) is installed but is not the default `java` on the development machine, which
  defaults to 26, and Gradle is not on the PATH] → Declare a Java 25 toolchain so the build does not depend on the
  default JDK; if Gradle does not auto-detect the installation, point it at the install path in a local, untracked
  Gradle property; bootstrap the Gradle wrapper once from a downloaded Gradle distribution. Pick a Gradle version that
  supports building for Java 25. CI uses the same wrapper.
- [The feed's HTML layout or `guid` scheme changes] → Raw payload always stored, sections nullable, fingerprint
  independent of layout; identity falls back to the canonical link if the guid is missing.
- [Feed text carries prompt injection that a downstream agent could follow] → Service never acts on it; every response
  labels it untrusted; the consuming auditor must also treat it as data (tracked in that change).
- [Large pages inflate agent context] → Page cap of 50 and default of 20; revisit with a summary view if callers need it.
- [JobRunr's dashboard listens on all interfaces over plain HTTP and exposes job controls (trigger, delete, requeue)] →
  Off by default; startup refuses to enable it without a username and a password of at least 12 characters;
  credentials are kept out of logs; the documentation requires a TLS reverse proxy or tunnel and network restrictions
  for any shared network. The bind address cannot be changed from configuration, which is why the controls are
  authentication and network placement rather than localhost binding.
- [JobRunr's documentation describes H2 as a development and test store] → Observed in the skeleton: JobRunr 9.0.3's own
  BOM pins H2 2.5.252, which overrides the 2.4.240 that Boot 4.1.2 manages, and the application starts with JobRunr on
  its H2 storage provider. Flyway logs a warning that this H2 is newer than the 2.3.232 it was verified with; the
  migrations in the persistence group are the real test. The standard-SQL schema and the shared data source keep a move
  to PostgreSQL a configuration change.
- [Embedded H2 allows only one process on the data file, so there is no second instance or rolling restart] → Accepted
  for a single internal service; documented, with a fast, clear startup failure if a second process tries.
- [A file database can be lost or corrupted with the host] → Data directory on durable storage, backups by copying it
  while stopped, and the feed itself is the source of truth: an empty store refills from the next ingestion (older,
  already-dropped feed items are not recoverable).
- [The JDK HTTP client resolves the host again when it connects, so the address checked by the validator cannot be
  pinned (a DNS-rebinding window)] → The window exists only if DNS for an allowlisted host (by default
  `radar.cloudflare.com`) is itself hostile, which is outside this service's control; redirects are re-validated on
  every hop and the destination is a fixed allowlist, not caller-supplied.
- [Origin rate-limits or blocks automated clients] → Once-a-day cadence, conditional requests, a descriptive User-Agent,
  and backoff on retries.
- [Feed terms of use] → Checked on 2026-11-10 and recorded in the README: Cloudflare's Website Terms reserve all rights,
  do not mention RSS or syndication, and restrict automated bots for AI uses unless `robots.txt` allows them; the Zero
  Wire page and `robots.txt` cannot be read by automated clients (Cloudflare challenge), so no feed-specific license was
  found. Unresolved: confirm with Cloudflare and legal or compliance before any use beyond a trial, keep the service
  internal, and keep `sourceUrl` with every record. The service's request footprint is one fetch per day plus retries.

## Migration Plan

New service with no existing data or consumers. Deploy the application with an empty data directory (Flyway creates the H2
file and schema), trigger one ingestion from the dashboard, and verify the tool through an MCP client. Rollback is
stopping the service; nothing depends on it until the auditor change ships.

## Open Questions

- Default cron time and time zone for the daily run.
