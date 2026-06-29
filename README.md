# etlp-mapper

A small, self-contained microservice for **low-code data transformation** using
the [Jute](https://github.com/HealthSamurai/jute.clj) DSL. Define a Jute template
(YAML/JSON), store it, and `POST` payloads to get conformant artifacts back —
handy for reshaping FHIR, HL7v2, or arbitrary JSON.

It runs on **SQLite out of the box** (no external database to stand up), and can
switch to **Postgres** for horizontal scale by changing a single environment
variable. No code changes either way.

## Features

- Persisted, versioned mappings (`/_history` per mapping) scoped by `org_id`.
- Apply a stored Jute template to a payload over HTTP.
- One-off template testing without persisting (`/mappings/test-template`).
- Optional LLM "copilot" to generate templates from examples (`/mappings/generate`).
- Optional OIDC (Keycloak) auth — on by default, easy to disable for local/demo.
- Optional outbound CDC webhook on mapping changes (off by default).

## Quickstart (SQLite, zero dependencies)

Requires Java + [Leiningen](https://leiningen.org/). No database server needed.

```sh
# 1. Point the app at a SQLite file (the jdbc:sqlite prefix selects the backend)
export JDBC_URL="jdbc:sqlite:/tmp/etlp-mapper.db?journal_mode=WAL&busy_timeout=5000"

# 2. Disable OIDC for a quick local spin (dev passthrough identity)
export OIDC_ENABLED=false

# 3. Create the schema, then run the server (defaults to port 3000)
lein run :duct/migrator
lein run
```

Or build an uberjar:

```sh
lein uberjar
java -jar target/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar :duct/migrator
java -jar target/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar
```

Create and apply a mapping:

```sh
# create a mapping (Jute template stored as YAML)
curl -X POST http://localhost:3000/mappings \
  -H 'Content-Type: application/json' \
  -d '{"title":"demo","content":{"yaml":"status: active"}}'
# -> 201 Created, Location: /mappings/1

# apply it to a payload (apply does NOT auto-wrap; pre-wrap under data)
curl -X POST http://localhost:3000/mappings/1/apply \
  -H 'Content-Type: application/json' \
  -d '{"data":{"resource":{"any":"payload"}}}'
# -> {"status":"active"}
```

See [`docs/SQLITE_ACA.md`](docs/SQLITE_ACA.md) for SQLite persistence options,
WAL/concurrency notes, and a sample Azure Container Apps deployment.

## Using Postgres instead

The SQLite backend is the default for ease of adoption. For many concurrent
writers or horizontal scaling, point `JDBC_URL` at Postgres (>= v14) and
redeploy — nothing else changes:

```sh
export JDBC_URL="jdbc:postgresql://localhost:5432/etlp?user=...&password=..."
lein run :duct/migrator
lein run
```

## HTTP API

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Index |
| `GET` | `/whoami` | Current identity |
| `GET` | `/mappings` | List mappings (scoped by org) |
| `POST` | `/mappings` | Create a mapping |
| `GET` | `/mappings/{id}` | Fetch a mapping |
| `PUT` | `/mappings/{id}` | Update a mapping |
| `DELETE` | `/mappings/{id}` | Delete a mapping |
| `POST` | `/mappings/{id}/apply` | Apply a stored template to a payload |
| `GET` | `/mappings/{id}/_history` | Version history |
| `GET` | `/mappings/{id}/_history/{txnid}` | A historical version |
| `POST` | `/mappings/test-template` | Apply an ad-hoc template (no persistence) |
| `POST` | `/mappings/generate` | Copilot: synthesize a template (requires LLM config) |
| `GET` | `/openapi.json` | OpenAPI spec |
| `GET` | `/jute-dsl-spec.json` | Jute DSL spec used by the copilot |

> **Note:** `/mappings/{id}/apply` does **not** auto-wrap the payload (unlike
> `/test-template` and `/generate`). Wrap it as `{"data": {"resource": <inner>}}`.

## Configuration

| Var | Purpose | Default |
|---|---|---|
| `JDBC_URL` | DB connection; `jdbc:sqlite:` selects SQLite, else Postgres | — |
| `DB_DIALECT` | Force `sqlite` regardless of URL | (inferred) |
| `PORT` | HTTP port | `3000` |
| `OIDC_ENABLED` | Set `false` to bypass auth with a dev identity | `true` |
| `OIDC_ISSUER` / `OIDC_AUDIENCE` / `OIDC_JWKS_URI` | Keycloak OIDC config | — |
| `DEV_ORG_ID` | org_id used by the dev passthrough identity | `dev-org` |
| `KB_HOOK_ENABLED` / `KB_HOOK_URL` / `KB_HOOK_SECRET` | Optional outbound CDC webhook | disabled |
| `COPILOT_LLM_PROVIDER` / `AZURE_OPENAI_*` / `ANTHROPIC_API_KEY` | LLM copilot (only for `/mappings/generate`) | — |

### OIDC authentication

By default endpoints are secured with Keycloak OIDC. Configure:

```sh
OIDC_ISSUER=http://localhost:8080/realms/<realm>
OIDC_AUDIENCE=<audience>
OIDC_JWKS_URI=http://localhost:8080/realms/<realm>/protocol/openid-connect/certs
```

Then call endpoints with a bearer token:

```sh
curl -H "Authorization: Bearer $TOKEN" http://localhost:3000/whoami
```

For local development or demos, set `OIDC_ENABLED=false` to use a dev
passthrough identity (org `dev-org`, overridable via `DEV_ORG_ID`).

## Development

```sh
lein duct setup   # one-time: create local config files
lein repl
```

```clojure
user=> (dev)
user=> (go)       ; starts the server
dev=>  (reset)    ; reload changed files
```

### Testing

```sh
lein test
# SQLite backend round-trip integration test:
lein test etlp-mapper.sqlite-test
```

## License

Copyright © 2024 Rahul Gaur

This program and the accompanying materials are made available under the terms
of the Eclipse Public License 2.0, which is available at
<http://www.eclipse.org/legal/epl-2.0>. See [`LICENSE`](LICENSE).

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at <https://www.gnu.org/software/classpath/license.html>.
