# Operations

How the daily ingestion runs, how to see what it did, and how to run it by hand. Everything here was run against a real
service and the real feed (see the checks at the end).

## What runs, and when

- A recurring job named **Zero Wire ingestion** (id `zero-wire-ingestion`) fetches the feed once a day. The default is
  `0 3 * * *` in UTC; change it with `INTEL_SCHEDULE_CRON` and `INTEL_SCHEDULE_ZONE`.
- After a restart the schedule is simply registered again under the same id: there is never a second schedule, and the
  first run is the next cron time, not the moment of the start.
- JobRunr's open-source edition does not make up runs that were missed while the service was down. So at start-up, when
  no ingestion has ever succeeded or the last success is older than `INTEL_SCHEDULE_CATCHUPAFTER` (default 24 hours),
  **one catch-up ingestion** is queued. Restarting again within the same hour does not queue a second one.
- A run that fails or is refused (feed unreachable, wrong content, destination not allowed) makes its job fail, and
  JobRunr retries it up to 3 times with growing back-off. The next daily run tries again regardless.
- A failed run never changes stored records: all writes of a run happen in one transaction.
- `INTEL_SCHEDULE_ENABLED=false` turns off the daily job and the catch-up (the MCP tool keeps serving what is stored).

## The operations dashboard

The dashboard is JobRunr's own. It shows the jobs by state, failures and retries, the recurring job with its next run,
and the background servers, and it can run the ingestion now. It is **off unless you enable it**, and it cannot be
enabled without credentials.

### Enable it

Set the dashboard's settings as environment variables (JobRunr's own keys):

| Variable | Meaning |
|---|---|
| `JOBRUNR_DASHBOARD_ENABLED` | `true` to serve the dashboard (default `false`) |
| `JOBRUNR_DASHBOARD_USERNAME` | Basic-authentication user name (required when enabled) |
| `JOBRUNR_DASHBOARD_PASSWORD` | Basic-authentication password, **at least 12 characters** (required when enabled) |
| `JOBRUNR_DASHBOARD_PORT` | Port to listen on (default `8000`) |

Use environment variables, not command-line arguments: arguments are visible in process listings.

```
# bash
./gradlew bootJar
JOBRUNR_DASHBOARD_ENABLED=true JOBRUNR_DASHBOARD_USERNAME=ops JOBRUNR_DASHBOARD_PASSWORD='a-long-secret-1' \
  java -jar build/libs/intel-service-0.1.0-SNAPSHOT.jar
```

```
# PowerShell
./gradlew bootJar
$env:JOBRUNR_DASHBOARD_ENABLED = 'true'; $env:JOBRUNR_DASHBOARD_USERNAME = 'ops'
$env:JOBRUNR_DASHBOARD_PASSWORD = 'a-long-secret-1'
java -jar build/libs/intel-service-0.1.0-SNAPSHOT.jar
```

If the dashboard is enabled without a username, without a password, or with a password shorter than 12 characters, the
service refuses to start and says which setting is wrong (it never prints the password).

### Open it

Browse to `http://<host>:8000/dashboard` and sign in with the username and password. Without them every request is
rejected with 401. The startup log line `JobRunr Dashboard using H2StorageProvider started at ...` confirms it is up.

The pages are served from the same API you can call from a script (add `-u user:password`):

| Question | Call |
|---|---|
| Which recurring jobs exist, with cron, zone and next run? | `GET /api/recurring-jobs` |
| What is queued, running, done, failed, or waiting for a retry? | `GET /api/jobs?state=ENQUEUED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `SCHEDULED` |
| What happened in one job (state history, failure, log)? | `GET /api/jobs/{id}` |
| Is a background server running? | `GET /api/servers` |
| Run the ingestion now | `POST /api/recurring-jobs/zero-wire-ingestion/trigger` (answers 204) |

### What each job tells you

Every ingestion job writes one line to its own log, which the dashboard shows on the job's page (and `GET /api/jobs/{id}`
returns):

```
Run 08e56f66-...: SUCCESS - seen 8, new 8, updated 0, unchanged 0, skipped 0
Run 00ba56b3-...: NO_CHANGE - seen 8, new 0, updated 0, unchanged 8, skipped 0
Run 7d1c0a52-...: FAILED - failed: HTTP 503 from 'radar.cloudflare.com'
```

The run id is the id in the `ingestion_run` table and in the service log, so the three always line up.

| Outcome | Meaning |
|---|---|
| `SUCCESS` | The feed was read and at least one item was created or updated |
| `NO_CHANGE` | The feed was read and nothing differed from what is stored |
| `FAILED` | The fetch or parse failed; stored records are untouched. The reason says why, for example `failed: HTTP 500 from '...'`, `failed: timed out after PT30S ...`, `failed: invalid feed: ...` |
| `REFUSED` | The destination was refused by policy, for example `refused: redirect target host '...' is not in the allowed hosts` |

`skipped` counts feed items that could not be stored (no title, identity or usable link); they never fail the run.

### Run it now

"Trigger now" on the recurring job in the dashboard, or the `POST` above, queues the same job the schedule runs. It is
recorded as a **scheduled** run, because the dashboard runs the recurring job itself. A failed job can also be
requeued from the dashboard.

### Exposure: read this before enabling it on a shared network

JobRunr's dashboard server listens on **every network interface**, speaks **plain HTTP**, and has no setting to choose
a bind address. Basic authentication over plain HTTP sends the password in the clear. So:

- put a TLS-terminating reverse proxy in front of it, or reach it through an SSH or VPN tunnel;
- close the dashboard port to everything except the proxy or the tunnel (host firewall or network rules);
- use a long, unique password and pass it by environment variable.

The dashboard can trigger, requeue and delete jobs, so treat access to it as operator access.

## Health and metrics

Both are served on the application port under `/actuator`. When `INTEL_ACCESS_TOKEN` is set they need
`Authorization: Bearer <token>`, except the two probes (`/actuator/health/liveness` and `/readiness`), which stay open.

```
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8080/actuator/health
```

- **Health.** The `ingestion` component is `UP` while the data is fresh and `DOWN` when the last successful ingestion is
  older than `INTEL_FRESHNESS_STALEAFTER` (default 36 hours) or none ever succeeded; the overall status then answers
  `503`. A failed run that is still being retried does not make it `DOWN` while the data is fresh. Its details show the
  freshness, the time of the last success, and the last run with its counts or failure reason. Point container
  liveness and readiness at the probe paths, not at the overall status: a stale feed should alert you, not restart the
  service.
- **Metrics.** `GET /actuator/metrics` lists them; read one with `?tag=`:
  `/actuator/metrics/intel.ingestion.items?tag=result:new`. `intel.ingestion.runs` counts runs by `outcome`
  (`SUCCESS`, `NO_CHANGE`, `FAILED`, `REFUSED`); `intel.ingestion.items` counts feed items by `result` (`new`, `updated`,
  `unchanged`, `skipped`). They start at zero when the service starts. JobRunr's own job and server metrics are published
  there as well (names starting with `jobrunr.`).
- **Logs.** Each run logs one line with its id, trigger, outcome and counts, for example
  `Ingestion run 08e56f66-... (CATCH_UP): SUCCESS - seen 8, new 8, updated 0, unchanged 0, skipped 0`; a failed run logs
  its id and the stable reason. Feed text never appears in the logs: an item that cannot be stored is logged by its
  position only (`skipping malformed feed item #2 (item has no title)`).

## Reading the run history from the database

The dashboard shows job state and each job's log line. The full history, including the counts of every run, is in the
`ingestion_run` table. The database is embedded and allows **one process at a time**, so stop the service first, then
use the H2 shell that ships inside the application jar:

```
java -Dloader.main=org.h2.tools.Shell -cp build/libs/intel-service-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  -url "jdbc:h2:file:./data/intel" -user sa -password "" \
  -sql "SELECT SUBSTRING(id,1,8) AS run, trigger_kind, outcome, items_seen, items_new, items_updated, items_unchanged, items_skipped, error_reason FROM ingestion_run ORDER BY finished_at"
```

Replace `./data` with your data directory. Output looks like:

```
RUN      | TRIGGER_KIND | OUTCOME   | ITEMS_SEEN | ITEMS_NEW | ITEMS_UPDATED | ITEMS_UNCHANGED | ITEMS_SKIPPED | ERROR_REASON
08e56f66 | CATCH_UP     | SUCCESS   | 8          | 8         | 0             | 0               | 0             | null
00ba56b3 | SCHEDULED    | NO_CHANGE | 8          | 0         | 0             | 8               | 0             | null
```

The service also logs one INFO line per run (`Ingestion run <id> (<trigger>): <outcome> - seen ..., new ...`).

## What the feed origin sends

Checked on 2026-11-10: `https://radar.cloudflare.com/security/zero-wire/rss` answers `200` with
`Cache-Control: public, max-age=300` and **no `ETag` or `Last-Modified`**, and it ignores conditional requests. Every run
therefore downloads the whole feed (about 65 KB, 8 items at the time) and the per-item fingerprints decide what
changed. If the origin ever starts sending validators, the service uses them without any change.
