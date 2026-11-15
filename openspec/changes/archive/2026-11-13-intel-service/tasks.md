# Tasks

Assumptions recorded here because the design leaves them open: the service lives in a new `intel-service/` directory at
the repository root; the build is Gradle with the Kotlin DSL and the Gradle wrapper (Gradle is not on the development
machine's PATH, so the wrapper is bootstrapped once from a downloaded distribution); the build targets a Java 25
toolchain (a Microsoft JDK 25.0.5 is installed, while the default `java` is 26); data is an embedded H2 database in a
file under a configurable data directory; the default daily run is 03:00 UTC and is configurable; the JobRunr dashboard
is the operations tool (job states, failures, trigger now) and is off unless enabled with mandatory credentials (it
listens on all interfaces over plain HTTP, so deployments add TLS and network restrictions in front of it).

## 1. Skeleton and build

- [x] 1.1 Bootstrap the Gradle wrapper in `intel-service/` from a downloaded Gradle distribution that supports building
      for Java 25; verify `./gradlew --version` runs and the wrapper files are present
- [x] 1.2 Write `build.gradle.kts` with a Java 25 toolchain, the Spring Boot 4.1.2 Gradle plugin, the Spring AI 2.0.2
      BOM, the JobRunr 9.0.3 Boot 4 starter, the Spring AI MCP server web starter, Flyway, the H2 driver and an HTML parser
      (the feed itself is read with the JDK's hardened XML parser, see design D7); verify `./gradlew javaToolchains` lists the JDK 25 install (if it is not detected, add its path in a local,
      untracked Gradle property and note it in the README) and `./gradlew dependencies` shows no milestone or RC artifacts
- [x] 1.3 Add an application class and a context-load test on H2; verify `./gradlew build` passes and `./gradlew bootRun`
      starts the app with JobRunr running on the resolved H2, or record in design.md the Boot version Spring AI 2.0.2
      requires if the pinned set does not resolve
- [x] 1.4 Add a typed configuration class for feed URL, allowed hosts, cron, time zone, size and time limits, staleness
      threshold, bind address, token and data directory, with validation and environment overrides; verify a test shows
      defaults, overrides, and a startup failure for a missing feed URL
- [x] 1.5 Write `intel-service/README.md` covering prerequisites (JDK 25, no database server), build, run and the
      configuration table; verify a clean clone follows it to a running service

## 2. Persistence

- [x] 2.1 Write Flyway migrations for `vulnerability`, `vulnerability_cve`, `vulnerability_tag`, `vulnerability_link`,
      `feed_source_state` and `ingestion_run` using standard SQL only (no array or JSON columns, no engine-specific
      features); verify the migrations apply to a fresh in-memory and a fresh file-mode H2 database, and that re-applying
      them on an existing file changes nothing
- [x] 2.2 Implement the upsert by feed item identity and content fingerprint, with `sort_at` set once at insert; verify
      tests for a new item, an unchanged duplicate (no write), and a corrected item that keeps first-seen, published and
      `sort_at` while changing content and last-updated
- [x] 2.3 Implement the keyset query ordered by `sort_at` then id with the scope, look-back, CVE and product/tag filters
      (tag exact and case-insensitive; title and affected-deployments by parameterized substring, with `%` and `_`
      escaped); verify tests for each filter, combined filters, ordering, and no skip or repeat when an item is corrected
      between two pages
- [x] 2.4 Implement the repositories for feed source state and ingestion runs, including the last-successful-ingestion
      lookup; verify tests for never-ingested, success and failed-after-success histories
- [x] 2.5 Verify durability and the single-instance rule: a file-mode test ingests items, restarts the application on the
      same data directory and finds them; a second process opening the same data file fails fast with a clear message
- [x] 2.6 Document the schema, the standard-SQL rule, the data directory, backup by copying while stopped, and the
      single-instance rule in `intel-service/docs/data-model.md`; verify every table and column in the document exists in
      the migrations (a test or script compares them)

## 3. Safe feed fetching

- [x] 3.1 Implement the destination validator: HTTPS only, host on the allowlist, resolved addresses not loopback,
      private, link-local or metadata-service; verify unit tests for each rejected class and for an allowed host
- [x] 3.2 Implement the fetcher with an HTTP client that does not follow redirects itself, follows at most a few,
      re-validates every target, reads through a bounded stream, enforces connect and total timeouts and an XML content
      type, and sends stored validators and a descriptive User-Agent; verify tests against a local test server for a
      normal fetch, a 304, an HTTP redirect, a foreign-host redirect, a redirect to a private address, an oversized
      body, a slow body and a wrong content type
- [x] 3.3 Record the refusal or failure reason for each rejected fetch in a form the ingestion run can store; verify a
      test asserts the reason text for each scenario in 3.2

## 4. Parsing and normalization

- [x] 4.1 Save a copy of the real Zero Wire feed as a test fixture (download it fresh and keep the file unmodified);
      verify the fixture file exists under `src/test/resources` and is referenced by the parser tests
- [x] 4.2 Implement the hardened feed reader that forbids DOCTYPE and external entities and limits entries and nesting;
      verify tests that a document with an external entity and one with a DOCTYPE are rejected and that the fixture
      still parses
- [x] 4.3 Implement the normalizer: identity from the guid (falling back to the canonical link), tags from categories,
      CVE ids by pattern over title, description and content, the `<h2>` sections with an "other" bucket, evidence and
      source links, the last-reviewed time from the `<time>` element, and the content fingerprint; verify fixture tests
      for item count, an item with CVE ids (for example the OpenSSH and Brotli items), an item without CVE ids, missing
      sections, a missing guid, and a changed body producing a different fingerprint
- [x] 4.4 Verify the raw payload is stored verbatim and that feed text containing instructions or links is stored
      unchanged and triggers no outbound request (a test with a hostile item and a request-counting test server)

## 5. Ingestion job

- [x] 5.1 Implement the ingestion service: fetch, parse fully, then write all changes in one transaction, update the feed
      source state and write an `ingestion_run` row; verify tests for new items, an unchanged feed (no record changes,
      successful run), a 304 response, and a failure injected mid-write that leaves previously stored data untouched
- [x] 5.2 Register the JobRunr recurring job from the configured cron and time zone, with retry on failure; verify a test
      that the recurring job exists with the configured expression and that starting the app twice on the same data
      directory does not create a duplicate
- [x] 5.2a Implement the startup catch-up (enqueue one ingestion at startup when none has ever succeeded or the last
      success is older than 24 hours); verify tests for an empty store, a 30-hour-old success, a 2-hour-old success (no
      extra run, one schedule after the restart), the first scheduled run being the next cron time rather than startup,
      and an ingestion interrupted mid-run then repeated leaving the same stored records
- [x] 5.3 Verify the manual trigger end to end with the background server running and a scripted feed source: trigger the
      recurring job the way the dashboard's "Trigger now" does, and enqueue the manual job through the job scheduler;
      check each produces an `ingestion_run` row of the same shape as a scheduled run, and that a failing run leaves the
      JobRunr job failed and retried
- [x] 5.3a Enable JobRunr's dashboard only with credentials: add a startup check that refuses
      `jobrunr.dashboard.enabled=true` without a username and a password of at least 12 characters (naming the missing
      setting and never echoing the password), keep it off by default, and keep the password out of logs; verify tests
      for off by default, enabled with credentials (401 without or with wrong credentials, 200 with them), enabled
      without credentials, a weak password, and a log capture that never contains the password
- [x] 5.4 Write `intel-service/docs/operations.md` for the schedule, how to enable and log in to the dashboard, what it
      shows (jobs by state, failures, retries, recurring job and next run, and each job's own log line with the run id and
      its counts or failure reason), "Trigger now", reading `ingestion_run` history, and the exposure advice (all interfaces, plain HTTP: TLS reverse proxy or tunnel plus network
      restrictions); verify the documented steps work against a locally running service

## 6. Query service and MCP tool

- [x] 6.1 Implement the query service with argument validation (unknown scope, `recent` without a look-back, look-back
      outside 1 to 365, malformed CVE id), page size default 20 and cap 50, and opaque cursors that encode the last
      `(sort_at, id)` and a hash of the query; verify tests for every invalid-argument case, the page-size clamp, and
      rejection of a cursor from a different query
- [x] 6.2 Implement the freshness block (last successful ingestion, stale flag from the configured threshold, and a status
      of ok, stale or never-ingested) and the untrusted-content notice on every response; verify tests for fresh with no
      match, stale, and never-ingested
- [x] 6.3 Expose `listVulnerabilities` through the MCP server starter as the only tool, returning records without the raw
      payload; verify a test that connects with an MCP client over HTTP, lists exactly one tool, and gets the response
      shape from the design
- [x] 6.4 Confirm which HTTP transport the starter provides and works with Claude CLI and Copilot CLI (streamable HTTP
      preferred, SSE otherwise) and record the result in design.md; verify by connecting the Claude CLI to the running
      service and listing the tool, and by repeating with the Copilot CLI if it is installed

## 7. Access protection

- [x] 7.1 Bind to `127.0.0.1` by default; refuse to start when a non-loopback bind is configured without a token; verify a
      test for the default bind and a test that the startup failure message names the missing token
- [x] 7.2 Implement the bearer-token filter with a constant-time comparison in front of the MCP endpoint, rejecting before
      any tool code runs; verify tests for a valid token, a missing token, a wrong token, and that rejected requests
      return no records
- [x] 7.3 Keep the two surfaces independent: the MCP bind address and token settings must not change the dashboard's
      protection, and the dashboard credentials must never be accepted by the MCP endpoint or the reverse; verify tests
      for both directions with both enabled
- [x] 7.4 Document access configuration and how to register the server with Claude CLI and Copilot CLI (with and without
      a token) in `intel-service/docs/connecting.md`; verify the documented commands connect and list the tool

## 8. Observability

- [x] 8.1 Add an Actuator health indicator for last ingestion status and staleness and counters for new, updated,
      unchanged and failed items per run; verify tests for healthy, stale and failed states and that the counters move
      after a run
- [x] 8.2 Verify logs for ingestion and rejected fetches contain run id, reason and counts and no feed body text; check
      with a test that captures log output for a hostile item

## 9. Integration checks

- [x] 9.1 Run the whole service with its H2 file database against the real feed once through the manual trigger, then call
      `listVulnerabilities` with the Claude CLI for scope `all`, `recent` and a CVE id; verify the records match the
      feed and the freshness block is `ok`
- [x] 9.2 Stop and restart the service with a bearer token configured; verify the stored records survived, the same
      queries return the same results, and an unauthenticated call is rejected
- [x] 9.3 Run `./gradlew build` and `openspec validate "intel-service" --strict`; verify both pass, and walk the spec's
      scenarios once to confirm each has a test or a recorded manual check
- [x] 9.4 Check the feed's terms of use for storing and serving its content to internal clients and record the outcome in
      `intel-service/README.md`; verify the note names the source page checked and the date
