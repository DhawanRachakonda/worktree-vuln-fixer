# intel-service

Ingests the Cloudflare Radar Zero Wire vulnerability feed once a day, keeps it in a local database, and serves it to
other applications through a read-only MCP tool. Planning artifacts live in
`openspec/changes/intel-service/` (proposal, spec, design, tasks).

## What it does

- Once a day (and once at start-up when the store is empty or stale) it reads the feed, stores new and corrected items in
  an embedded H2 file database, and records every run.
- It publishes one read-only MCP tool, `listVulnerabilities`, over streamable HTTP, with freshness information and
  paging. Record text is treated as untrusted data throughout.
- It is operated through JobRunr's dashboard (off unless enabled with credentials), Actuator health and metrics, and
  logs that never contain feed text.

## Documentation

| Guide | Covers |
|---|---|
| [docs/connecting.md](docs/connecting.md) | Starting the service, who may connect, registering it with Claude Code and Copilot CLI, the tool's arguments and answers |
| [docs/operations.md](docs/operations.md) | The schedule and start-up catch-up, the dashboard, health, metrics and logs, reading run history |
| [docs/data-model.md](docs/data-model.md) | The database schema, the single-instance rule, backup |
| [docs/spec-coverage.md](docs/spec-coverage.md) | Every scenario of the spec and the test or live check that proves it |

## Feed terms of use

*Checked on 2026-11-10. This is a record of what was found, not legal advice.*

- **Pages checked:** Cloudflare's Website Terms of Use, <https://www.cloudflare.com/website-terms/> ("Effective August
  1, 2025"), which the Cloudflare Radar page links from its footer; and Cloudflare's Radar documentation,
  <https://developers.cloudflare.com/radar/>, which licenses *Radar API data* under CC BY-NC 4.0 (a statement about
  the API, not about the Zero Wire feed).
- **What they say:** §6 reserves all rights in the site's materials ("Except as expressly authorized by Cloudflare you
  may not make use of the Materials") and does not distinguish internal from public use. RSS and syndication are not
  mentioned. §8 forbids using automated bots to access, scan, scrape or copy the materials for artificial-intelligence
  or machine-learning purposes unless the bot's user agent is explicitly allowed in the site's `robots.txt`. §7 forbids
  overburdening Cloudflare's servers.
- **What could not be checked:** the Zero Wire page and `radar.cloudflare.com/robots.txt` answer automated clients with
  a Cloudflare challenge (HTTP 403), so no feed-specific license text was found and `robots.txt` could not be read. The
  RSS endpoint itself answers `200` to this service's user agent.
- **Consequence:** nothing found expressly authorizes storing the feed and serving it to other applications, and because
  the consumers of this service are AI agents, §8 may apply. Before using the service beyond a trial, confirm the terms
  with Cloudflare and your own legal or compliance team, keep the service internal, and keep the link to each source
  briefing (`sourceUrl`) in front of its readers. The service's footprint is deliberately small: one request per day plus
  at most three retries, a descriptive `User-Agent`, and nothing re-published outside the MCP tool.

## Prerequisites

- **JDK 25.** The build declares a Java 25 toolchain, so the JDK that launches Gradle does not matter, but a JDK 25
  must be installed. If `./gradlew javaToolchains` does not list it, tell Gradle where it is in your **user-level**
  `~/.gradle/gradle.properties` (never in the project):

  ```properties
  org.gradle.java.installations.paths=C:/Program Files/Microsoft/jdk-25.0.5.21-hotspot
  ```

- Nothing else. There is no database server: data is an embedded H2 file database. Gradle itself comes from the
  wrapper (`gradlew` / `gradlew.bat`, Gradle 9.9.0, checksum-pinned).

## Build and run

```
./gradlew build        # compile and run all tests
./gradlew bootRun      # start the service on http://127.0.0.1:8080
curl http://127.0.0.1:8080/actuator/health
```

On Windows use `gradlew.bat` in `cmd` or PowerShell. Stop the service with Ctrl+C.

## Data and single-instance rule

The H2 database file is `<data dir>/intel.mv.db` (default `./data/intel.mv.db`). It holds the vulnerability records and
the job scheduler's state. Only **one process can open the file at a time**; a second instance fails to start rather
than risk corruption. Back up by copying the data directory while the service is stopped.

## Configuration

Every setting can be overridden by an environment variable. Invalid values stop the service at startup with a message
that names the setting.

| Setting | Environment variable | Default | Meaning |
|---|---|---|---|
| `intel.data-dir` | `INTEL_DATA_DIR` | `./data` | Directory of the H2 database file |
| `server.address` | `INTEL_BIND_ADDRESS` | `127.0.0.1` | Address the HTTP server binds to |
| `intel.feed.url` | `INTEL_FEED_URL` | `https://radar.cloudflare.com/security/zero-wire/rss` | Feed to ingest; must be `https` and its host must be allowed |
| `intel.feed.allowed-hosts` | `INTEL_FEED_ALLOWEDHOSTS` | `radar.cloudflare.com` | Comma-separated hosts the fetcher may contact |
| `intel.feed.max-bytes` | `INTEL_FEED_MAXBYTES` | `5MB` | Largest accepted feed response |
| `intel.feed.connect-timeout` | `INTEL_FEED_CONNECTTIMEOUT` | `10s` | Connection timeout |
| `intel.feed.total-timeout` | `INTEL_FEED_TOTALTIMEOUT` | `30s` | Whole-request timeout |
| `intel.feed.max-redirects` | `INTEL_FEED_MAXREDIRECTS` | `3` | Redirects followed (0 to 10), each re-validated |
| `intel.feed.user-agent` | `INTEL_FEED_USERAGENT` | `intel-service/0.1 (...)` | User-Agent sent to the feed origin |
| `intel.schedule.cron` | `INTEL_SCHEDULE_CRON` | `0 3 * * *` | Daily ingestion time (5-field cron) |
| `intel.schedule.zone` | `INTEL_SCHEDULE_ZONE` | `UTC` | Time zone of the cron expression |
| `intel.schedule.catch-up-after` | `INTEL_SCHEDULE_CATCHUPAFTER` | `24h` | Start-up catch-up when the last success is older than this |
| `intel.schedule.enabled` | `INTEL_SCHEDULE_ENABLED` | `true` | `false` turns off the daily job and the start-up catch-up |
| `intel.freshness.stale-after` | `INTEL_FRESHNESS_STALEAFTER` | `36h` | Age after which responses report stale data |
| `intel.access.token` | `INTEL_ACCESS_TOKEN` | none | Bearer token for the MCP endpoint (blank means none) |
