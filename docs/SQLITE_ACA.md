# Running etlp-mapper on SQLite (Azure Container Apps)

The mapper supports two interchangeable backends, selected at runtime by
`JDBC_URL`. Use SQLite to deploy on ACA without standing up (and paying for) a
Postgres instance. Postgres remains the default and is unchanged.

## How backend selection works

`etlp-mapper.main` inspects the environment at boot:

- `JDBC_URL` starts with `jdbc:sqlite:` (or `DB_DIALECT=sqlite`) -> SQLite
  backend: the `:duct.profile/sqlite` migration overlay runs, and the JSON
  marshalling shim (`etlp-mapper.sqlitetypes`) is loaded.
- anything else -> Postgres (the existing behaviour).

Nothing else in the service changes. All routes, auth (OIDC), the Jute engine,
the copilot, and the KB hook are backend-agnostic.

## Environment variables

| Var | SQLite value |
|---|---|
| `JDBC_URL` | `jdbc:sqlite:/data/etlp-mapper.db?journal_mode=WAL&busy_timeout=5000` |
| `OIDC_ISSUER` / `OIDC_AUDIENCE` / `OIDC_JWKS_URI` | unchanged (still required) |
| `COPILOT_LLM_PROVIDER`, `AZURE_OPENAI_*` / `ANTHROPIC_API_KEY` | unchanged (only if `/mappings/generate` is used) |

`journal_mode=WAL` lets readers run concurrently with the single writer.
`busy_timeout=5000` makes writers wait up to 5s for the lock instead of failing
fast with `SQLITE_BUSY`. Both are Xerial-supported URL params.

## Persistence: pick one

SQLite is a single file. ACA container storage is ephemeral, so:

- **Ephemeral (simplest, demo/pilot):** point `JDBC_URL` at a container-local
  path. The DB is rebuilt on every cold start (migrations run idempotently via
  the `ragtime_migrations` table). Data is lost on restart/scale. Fine for
  "stand it up and exercise the API" while you defer Postgres.
- **Persisted (Azure Files volume):** mount an Azure Files share at `/data` and
  point `JDBC_URL` there. Data survives restarts.

> [!IMPORTANT]
> **Single replica only.** SQLite is one-writer, and WAL over an SMB/Azure Files
> mount does not coordinate multiple writers. Set `--min-replicas 1
> --max-replicas 1`. This is the deliberate trade-off for skipping Postgres; when
> you need horizontal scale or many concurrent writers, switch `JDBC_URL` back to
> `jdbc:postgresql://...` and redeploy. No code change.

## Deploy (sketch)

```bash
# Build & push (amd64 for ACA)
docker build --platform linux/amd64 -t <registry>.azurecr.io/etlp-mapper:sqlite .
docker push <registry>.azurecr.io/etlp-mapper:sqlite

# Ephemeral SQLite
az containerapp create \
  --name etlp-mapper \
  --resource-group <rg> \
  --environment <aca-env> \
  --image <registry>.azurecr.io/etlp-mapper:sqlite \
  --target-port 3000 --ingress external \
  --min-replicas 1 --max-replicas 1 \
  --env-vars \
    JDBC_URL="jdbc:sqlite:/data/etlp-mapper.db?journal_mode=WAL&busy_timeout=5000" \
    OIDC_ISSUER=... OIDC_AUDIENCE=... OIDC_JWKS_URI=...
```

For persistence, add an Azure Files storage to the ACA environment
(`az containerapp env storage set ...`) and attach it as a volume mounted at
`/data` (set via the container app YAML `volumes`/`volumeMounts`).

## What is different from Postgres (behaviour)

- `content` is stored as JSON TEXT, not `jsonb`. Round-trips identically through
  the API; there is no server-side JSON querying in this service, so nothing is
  lost functionally.
- History versioning: the `txnid` column is a sortable surrogate
  (`<timestamp>-<random>`) instead of a Postgres transaction id, because SQLite
  has no `txid_current()`. `/_history` and `/_history/{txnid}` work the same way.
- Timestamps are naive UTC text (no `timestamptz`).
- Triggers are inlined (SQLite has no stored functions); the history-on-update
  and `updated_at`-touch behaviour is preserved.

## Local smoke test

```bash
# create the schema in a throwaway file and start the server on SQLite
JDBC_URL="jdbc:sqlite:/tmp/etlp.db" lein run :duct/migrator
JDBC_URL="jdbc:sqlite:/tmp/etlp.db" lein run

# or run the backend round-trip test
lein test etlp-mapper.sqlite-test
```
