# Data model

intel-service keeps everything in one embedded **H2** database stored in a file. Schema changes are Flyway migrations
in `src/main/resources/db/migration/` (currently `V1__create_vulnerability_store.sql`). A test
(`DataModelDocTest`) fails when this document and the migrations disagree on tables or columns.

## Standard-SQL rule

Migrations use standard SQL only: `VARCHAR`, `CHAR`, `INTEGER`, `TEXT` and `TIMESTAMP WITH TIME ZONE`, primary and
foreign keys, plain indexes. No array or JSON columns, no engine-specific types or functions. CVE ids, tags and links
live in child tables instead of array columns. The reason is cost of change: moving to PostgreSQL later should mean a
different driver and JDBC URL, not a different schema. Queries follow the same rule (keyset pagination with plain
comparisons, `FETCH FIRST n ROWS ONLY`, parameterized `LIKE` with an explicit escape character).

## Data directory, backup and the single-instance rule

- The database file is `<data dir>/intel.mv.db`. The directory comes from `intel.data-dir` (environment variable
  `INTEL_DATA_DIR`, default `./data`). It also holds JobRunr's own `jobrunr_*` tables, created and managed by JobRunr
  in the same database, not by Flyway.
- **Backup:** stop the service and copy the whole data directory. Do not copy the file while the service runs.
- **Single instance:** the database is opened in embedded mode, so only one process can open the file at a time. A
  second instance on the same directory exits with code 1 and prints
  *"The intel-service data file is already in use by another process"* together with the action to take (stop the other
  instance or choose another `INTEL_DATA_DIR`). There is no second instance or rolling restart.
- **Recovery:** an empty or lost data directory refills from the feed on the next ingestion. Items that have already
  dropped off the feed are not recoverable.

## Time columns

| Column | Meaning |
|---|---|
| `published_at` | Time the feed published the item. Never changed after the first insert. |
| `last_reviewed_at` | "Last reviewed" time stated inside the item, when present. Changes when the item is corrected. |
| `first_seen_at` | When this service first stored the item. Never changed. |
| `updated_at` | When this service last changed the stored content. Equals `first_seen_at` until a correction arrives. |
| `sort_at` | Ordering key: `published_at`, or `first_seen_at` when the item has no published time. Set once at insert and never updated, so pagination cannot skip or repeat records when items are corrected. |

An item whose fingerprint (`content_sha256`) is unchanged is not written at all.

## Tables

### `vulnerability`

One row per feed item, identified by `(source_name, source_item_id)`.

| Column | Type | Notes |
|---|---|---|
| `id` | `VARCHAR(36)` | Primary key (UUID text). |
| `source_name` | `VARCHAR(100)` | Feed source, for example `zero-wire`. |
| `source_item_id` | `VARCHAR(500)` | The feed's item identifier (its `guid`, or the canonical link when missing). |
| `title` | `VARCHAR(1000)` | Item title. |
| `source_url` | `VARCHAR(2000)` | Canonical link of the item. |
| `published_at` | `TIMESTAMP WITH TIME ZONE` | Nullable. |
| `last_reviewed_at` | `TIMESTAMP WITH TIME ZONE` | Nullable. |
| `first_seen_at` | `TIMESTAMP WITH TIME ZONE` | |
| `updated_at` | `TIMESTAMP WITH TIME ZONE` | |
| `sort_at` | `TIMESTAMP WITH TIME ZONE` | See the time columns above. |
| `summary` | `TEXT` | The item's short description. |
| `section_overview` | `TEXT` | Text before the first heading: the status line and the introductory paragraph. |
| `section_updates` | `TEXT` | "Updates" section. |
| `section_details` | `TEXT` | "Details" section. |
| `section_risk` | `TEXT` | "Risk" section. |
| `section_affected` | `TEXT` | "Affected deployments" section. |
| `section_action` | `TEXT` | "Recommended action" section. |
| `section_coverage` | `TEXT` | "Cloudflare coverage" section. |
| `section_other` | `TEXT` | Any section with an unknown heading. |
| `raw_payload` | `TEXT` | The feed item exactly as received; never returned by the MCP tool. |
| `content_sha256` | `CHAR(64)` | Fingerprint over the normalized title, description and content. |

### `vulnerability_cve`

| Column | Type | Notes |
|---|---|---|
| `vulnerability_id` | `VARCHAR(36)` | Foreign key to `vulnerability`, deleted with it. |
| `cve_id` | `VARCHAR(30)` | CVE identifier found in the item. Primary key with `vulnerability_id`. |

### `vulnerability_tag`

| Column | Type | Notes |
|---|---|---|
| `vulnerability_id` | `VARCHAR(36)` | Foreign key to `vulnerability`, deleted with it. |
| `tag` | `VARCHAR(200)` | Tag (the feed's category). Primary key with `vulnerability_id`. |

### `vulnerability_link`

| Column | Type | Notes |
|---|---|---|
| `vulnerability_id` | `VARCHAR(36)` | Foreign key to `vulnerability`, deleted with it. |
| `position` | `INTEGER` | Order of the link in the item. Primary key with `vulnerability_id`. |
| `url` | `VARCHAR(2000)` | Evidence or source link. Stored as data; never fetched. |
| `label` | `VARCHAR(1000)` | Link text, nullable. |

### `feed_source_state`

One row per feed source: the validators for conditional requests and the last attempt.

| Column | Type | Notes |
|---|---|---|
| `source_name` | `VARCHAR(100)` | Primary key. |
| `etag` | `VARCHAR(500)` | Last `ETag`, nullable. |
| `last_modified` | `VARCHAR(100)` | Last `Last-Modified` header value, nullable. |
| `last_attempt_at` | `TIMESTAMP WITH TIME ZONE` | Nullable. |
| `last_error` | `TEXT` | Reason of the last failed attempt, null after a success. |

### `ingestion_run`

One row per run, scheduled, manual or start-up catch-up. The last successful ingestion time (used for freshness) is the
latest `finished_at` of a run whose outcome is `SUCCESS` or `NO_CHANGE`.

| Column | Type | Notes |
|---|---|---|
| `id` | `VARCHAR(36)` | Primary key (UUID text). |
| `source_name` | `VARCHAR(100)` | Feed source. |
| `trigger_kind` | `VARCHAR(20)` | `SCHEDULED`, `MANUAL` or `CATCH_UP`. |
| `started_at` | `TIMESTAMP WITH TIME ZONE` | |
| `finished_at` | `TIMESTAMP WITH TIME ZONE` | |
| `outcome` | `VARCHAR(20)` | `SUCCESS`, `NO_CHANGE`, `FAILED` or `REFUSED`. |
| `items_seen` | `INTEGER` | Items in the feed. |
| `items_new` | `INTEGER` | Items created. |
| `items_updated` | `INTEGER` | Items whose content changed. |
| `items_unchanged` | `INTEGER` | Items left alone because the fingerprint matched. |
| `items_skipped` | `INTEGER` | Malformed items that could not be stored (no title, identity or usable link). |
| `error_reason` | `TEXT` | Why the run failed or was refused, nullable. |
