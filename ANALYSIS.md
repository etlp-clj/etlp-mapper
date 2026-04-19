# Local Dev Environment Analysis

_Scope: etlp-mapper + mapify-io + etlp-base for Jute Copilot work._
_Date: 2026-04-15._

## Repo locations

All three repos are siblings in `/Users/aregee/Workspace/github.com/`:

- `etlp-mapper/` — Clojure/Duct microservice
- `mapify-io/` — React/Vite frontend (Mapping Studio)
- `etlp-base/` — Clojure CLI ETL pipeline (`datalake-poc` branch locally)

## Toolchain on this machine

| Tool | Version | Status |
|---|---|---|
| Java | OpenJDK 21.0.8 LTS | ok |
| Leiningen | 2.12.0 | ok |
| Node | v25.2.1 | ok |
| npm | 11.6.2 | ok |
| Docker | 28.4.0 | ok |
| PostgreSQL | — | **not installed / not running** |
| `pg_isready` | — | not in PATH |

No Homebrew `postgresql` service. No running `postgres` docker container.

## etlp-mapper

**Framework:** Duct 0.8.0 + Ataraxy routing + duct.module.sql + Jute 0.2.0-SNAPSHOT + Auth0 java-jwt / jwks-rsa. Clojure 1.10.3.

**Entry point:** `etlp-mapper.main/-main` (`src/etlp_mapper/main.clj`). Loads `resources/etlp_mapper/config.edn` with profiles `[:prod :dev]` and key `[:duct/daemon]`.

**Config files:**
- `resources/etlp_mapper/config.edn` — base + routes + migrations + handler wiring
- `dev/resources/dev.edn` — dev overrides (jetty port, JDBC, OIDC)

**Port:** `dev.edn` hardcodes `:duct.server.http/jetty {:port 3000}`. **No env var override** — the port is literal. The prompt asks for **3031**, so `dev.edn` must be edited (or wrapped with `#duct/env ["PORT" :or 3000]`).

**Port conflict:** port 3000 is currently held by **lithrim-ui** (user-confirmed, PID 76923, `node server.js --port 3000`). Moving etlp-mapper to 3031 is required — and matches the spec.

**Database:** `dev.edn` defaults JDBC to `jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres`, overridable via `JDBC_URL`. Duct's `ragtime` migrator creates the schema automatically on startup (`mappings`, `mappings_history`, triggers, `org_id` column).

**Auth:** `src/etlp_mapper/auth_component.clj` already has the dev bypass. When `OIDC_ENABLED=false`:
- `wrap-auth` / `wrap-require-org` / `require-role` all become a passthrough that injects:
  ```clojure
  {:method :dev
   :org/id (or (System/getenv "DEV_ORG_ID") "lithrim-dev")
   :claims {:sub "dev-user" :email "dev@lithrim.com" :roles ["owner"]}}
  ```
- Note: dev org id is `"lithrim-dev"` (overridable via `DEV_ORG_ID`), not `"lithrim-default"` as the prompt assumes. This matters for data isolation — seeded mappings must use the same `org_id` the dev identity carries, or list queries return empty.

**CORS:** `src/etlp_mapper/middlewares.clj` defines `cors-middleware` with a hardcoded allowlist already covering:
- `http://localhost:5173`, `http://127.0.0.1:5173`, `http://192.168.1.21:5173`
- `http://localhost:8000`, `http://127.0.0.1:8000`, `http://192.168.1.21:8000`

No action needed. (`http://localhost:3000` from the prompt is **not** in the allowlist — would need to be added if used, but we're moving the API to 3031 anyway, not serving a second frontend on 3000.)

**Routes:** wired in `config.edn`:
- `GET  /` — index
- `GET  /whoami`
- `GET  /mappings` — list (filtered by `org_id`)
- `POST /mappings` — create (`{title, content}`)
- `PUT  /mappings/:id` — update (`{content}`)
- `GET  /mappings/:id` — find
- `DELETE /mappings/:id`
- `GET  /mappings/:id/_history`
- `GET  /mappings/:id/_history/:version`
- `POST /mappings/:id/apply` — runs Jute template on `{data}`
- `POST /mappings/test` — ad-hoc compile+run (`{template, scope}`)
- `POST /parse-hl7`
- `GET  /openapi.json`
- `GET  /jute-dsl-spec.json`

No `/health` endpoint — the prompt's verification `curl /health` will 404. `/` or `/whoami` are the closest substitutes.

**Apply handler:** `src/etlp_mapper/handler/mappings.clj` — reads mapping row, parses `content.yaml` as YAML, `jt/compile`, invokes with `data`. Org-scoped: `WHERE id = ? AND org_id = ?`.

**No `.env` file.** Config is via process env vars at launch.

## mapify-io

**Stack:** Vite 5 + React 18 + TypeScript + shadcn/ui + @tanstack/react-query + CodeMirror + react-oidc-context + tailwind. Bundled with `@vitejs/plugin-react-swc`.

**Dev server:** `npm run dev` → `vite --host` on port **5173** (pinned in `vite.config.ts`). No proxy config — frontend calls the API directly via absolute URL.

**API base URL:** `src/config/constants.ts`:
```ts
BASE_URL: import.meta.env.VITE_API_BASE || "http://192.168.1.21:3000"
```

**Important:** the env var is `VITE_API_BASE` — **not** `VITE_API_BASE_URL` as the prompt says. Default points at a LAN IP that won't resolve here. Must be overridden.

**Auth bypass:** fully wired.
- `src/config/constants.ts`: `AUTH_DISABLED = import.meta.env.VITE_AUTH_DISABLED === "true"`
- `src/components/auth/AuthProvider.tsx`: when `AUTH_DISABLED`, returns `<>{children}</>` directly — no OIDC provider mounted.
- `src/hooks/useAuth.ts`: `useAuth = AUTH_DISABLED ? useDevAuth : useOidcAuthWrapper`. `useDevAuth` returns `{ isAuthenticated: true, isLoading: false, ... }` so `ProtectedRoute` renders immediately.

**No `.env.local` exists yet** — needs to be created.

## etlp-base

**Not a web service.** CLI tool using `cli-matic`, entry `etl.core/-main`.

**Dependencies of concern:** three SNAPSHOT deps from `org.clojars.aregee/`:
- `etlp 0.3.3-SNAPSHOT`
- `etlp-s3-connect 0.1.1-SNAPSHOT`
- `etlp-hl7v2 0.1.0` (also a dep of etlp-mapper)

SNAPSHOT versions may require local `lein install` from their source repos if Clojars doesn't have them. Build may fail with "Could not find artifact" until resolved. This is expected; flag and move on — the Jute Copilot work doesn't need etlp-base running.

**Env vars:** `:env-vars [".env-vars"]` via `lein-environ` + `lein-with-env-vars`. `.env-vars` file does not exist in the repo — may be needed for subcommand args at runtime, not for `lein uberjar`.

## Issues / deviations from the prompt

1. **Port:** prompt says 3031; `dev.edn` defaults to 3000 with no env override. Fix by editing `dev.edn`.
2. **Port 3000 is occupied** by an unrelated node process — do not reclaim; just move etlp-mapper to 3031.
3. **mapify-io env var name:** `VITE_API_BASE`, not `VITE_API_BASE_URL`.
4. **Dev org id:** `"lithrim-dev"`, not `"lithrim-default"`. Any seed data must use `org_id = 'lithrim-dev'` (or override with `DEV_ORG_ID=lithrim-default`).
5. **No `/health` endpoint** — verification command will 404. Use `/` or `/whoami` instead.
6. **PostgreSQL is absent.** Must start a docker container before `lein run` (migrations need a live DB).
7. **DB name mismatch:** prompt suggests DB `etlp_mapper` / user `etlp`; `dev.edn` default JDBC expects DB `postgres` / user `postgres`. Either match the default (simpler) or set `JDBC_URL`.
8. **etlp-base SNAPSHOT deps** may not resolve — flag build failure if it happens, don't block setup.

## Proposed next steps

- Phase 2: `docker run` a Postgres 15 container (matching either the prompt's `etlp/etlp_dev/etlp_mapper` or the dev.edn default `postgres/postgres/postgres`).
- Phase 3: edit `dev/resources/dev.edn` to set `:port 3031`; export `OIDC_ENABLED=false` and (if needed) `JDBC_URL`; start etlp-mapper via `lein run`.
- Phase 4: write `mapify-io/.env.local` with `VITE_AUTH_DISABLED=true` and `VITE_API_BASE=http://localhost:3031`; `npm install && npm run dev`.
- Phase 5: `cd etlp-base && lein deps && lein uberjar` — expect SNAPSHOT resolution risk.
- Phase 6: seed templates only if `GET /mappings` comes back empty. No existing seed script in-repo; defer to copilot implementation.
