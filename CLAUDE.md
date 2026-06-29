# etlp-mapper

Clojure microservice that executes [Jute](https://github.com/HealthSamurai/jute.clj) DSL
templates against incoming data payloads (FHIR, HL7v2, custom JSON) to produce
conformant artifacts. Mappings are persisted, versioned, and applied over HTTP.

## Stack

- **Lang:** Clojure (Leiningen), Duct/Integrant component system
- **HTTP:** Reitit + Ataraxy on Ring/Jetty (default port 3000, override with `PORT`)
- **DB:** SQL via `clojure.java.jdbc`. Two interchangeable backends selected at
  runtime by `JDBC_URL`:
  - **SQLite** (`jdbc:sqlite:...`) — zero-dependency default; see `docs/SQLITE_ACA.md`.
  - **Postgres** (`jdbc:postgresql://...`) — for horizontal scale / many writers.
- **DSL:** Jute (YAML/JSON template engine)
- **Copilot (optional):** LLM-driven template synthesis for `/mappings/generate`
  (OpenAI/Azure/Anthropic), enabled only when configured.

## Local Dev

```bash
lein repl       # nREPL for interactive development
lein run        # HTTP server (SQLite or Postgres per JDBC_URL)
lein test       # run unit + integration tests
```

## Backend selection

`etlp-mapper.main` inspects the environment at boot: a `JDBC_URL` starting with
`jdbc:sqlite` (or `DB_DIALECT=sqlite`) selects the SQLite backend — it swaps in
the SQLite migration set and loads the JSON-marshalling shim
(`etlp-mapper.sqlitetypes`). Anything else uses Postgres. All routes, auth, the
Jute engine, and the copilot are backend-agnostic. See `docs/SQLITE_ACA.md`.

## Conventions

- The mapping execution path spans several layers: persisted `mappings` row →
  `etlp-mapper.handler.mappings/apply` → the Jute engine → JSON response. When
  debugging a wrong/missing field in an applied artifact, confirm which layer is
  at fault (inspect the stored row, then the engine output) before editing —
  Jute template bugs and data-marshalling bugs look identical at the response.
- Apply endpoint: `POST /mappings/{id}/apply` does NOT auto-wrap the payload
  (unlike `/test-template` and `/generate`); the caller pre-wraps as
  `{data: {resource: <inner>}}`.
- YAML quoting: when a Jute expression body contains `: ! & * # | > "` or
  `[]{}` characters, the whole YAML scalar must be double-quoted. The formal
  rules live in `resources/etlp_mapper/jute_dsl_spec.json`, which is also the
  single source of truth rendered into the copilot prompt — do not drift the
  prompt out of the spec.
