# ETLP Mapper

Clojure service that executes Jute DSL templates against incoming data payloads (FHIR, HL7v2, custom JSON) to produce conformant artifacts. Pairs with `lithrim-backend` as the structural-validation tier of the Lithrim verification pipeline. See `LITHRIM_SSOT.md` in `lithrim-backend` for product context.

## Stack

- **Lang:** Clojure (Leiningen)
- **HTTP:** Reitit + Pedestal (port 3031)
- **DB:** MongoDB (`velto` database — same instance as lithrim-backend; `mappings` collection)
- **DSL:** Jute (custom YAML/JSON template engine)
- **Copilot:** OpenAI gpt-4 / Azure for `/mappings/generate` LLM-driven template synthesis

## Local Dev

```bash
lein repl       # nREPL on default port
lein run        # HTTP server on 3031
lein test       # run unit tests
```

## Diagnose-before-edit gate (mandatory)

When fixing any reported bug in mapping execution, template generation, or structural validation — especially when the symptom (wrong artifact, missing field, generation confidence drop) is driven by data flowing through multiple layers (Mongo `mappings` → engine → Jute interpreter → response) — the following gate is mandatory **before** opening any source file to edit:

1. **Enumerate the layers** between source-of-truth and surface. For mapping execution: `mappings.{id}` Mongo doc → `etlp-mapper.handler.mapping/apply` route → `etlp-mapper.engine.execute` → Jute interpreter → JSON response. For copilot generation: prompt builder → OpenAI/Azure call → parser → optional /test-template → confidence scoring.
2. **For each candidate layer, post the verbatim observation** in a fenced code block. Acceptable evidence:
   - `mongosh velto --eval 'db.mappings.findOne({_id: ObjectId(...)})'` — the actual Mongo state
   - `curl -X POST http://localhost:3031/mappings/{id}/test-template ...` — live engine output
   - The exact line of Clojure (with `file:line` reference) being claimed buggy
   - REPL session output showing `(jute.core/eval ...)` against the failing template
3. **State the diagnosis only after the evidence block is posted.** Reference the verbatim observations by line / quote.
4. **Tag every causal claim with confidence:** CONFIRMED (backed by evidence), INFERRED (chain of reasoning, no live check), HYPOTHESIS (untested, with falsification criteria).
5. **No PR description, session log, or template-comment may contain a root-cause claim without an evidence block above it.** Untagged claims are treated as HYPOTHESIS by default.

**Why this exists:** the cross-repo Lithrim guardrail (see `lithrim-backend/CLAUDE.md` and `lithrim-command-center/CLAUDE.md`) was added on `2026-05-02` after a session-2026-05-01 case-12 council mislabel diagnosis was stated confidently without pulling the persisted state — and the actual bug turned out to be in two downstream layers nobody had inspected. The same failure mode applies to ETLP: Jute template bugs often live in 4 layers (template body, runtime, validators, persisted mapping). Without forced evidence per layer, the wrong layer gets edited.

**See also:**
- `lithrim-backend/CLAUDE.md` (full gate text + canonical example)
- `resources/etlp_mapper/jute_dsl_spec.json` (Jute YAML-integration rules — single source of truth for the copilot prompt)

## Conventions

- Apply endpoint: `POST /mappings/{id}/apply` does NOT auto-wrap data (unlike `/test-template` and `/generate`); caller pre-wraps as `{data: {resource: <inner>}}` (see `lithrim-command-center/.lithrim/MEMORY.md` reference_etlp_mapper_apply_quirk).
- YAML quoting: when a Jute expression body contains `: ! & * # | > "` or `[]{}` chars, the whole YAML scalar must be double-quoted. See `resources/etlp_mapper/jute_dsl_spec.json` for the formal rules. Copilot prompt is rendered from this file (do NOT drift the prompt out of the spec).
- Connector-level structural validators live in `app/services/artifact_evaluator.py` on the `lithrim-backend` side; this repo only owns the mapping execution + generation.
