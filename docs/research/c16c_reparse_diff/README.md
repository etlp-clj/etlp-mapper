# C16-C Sub-3 Path 1: Re-parse + Before/After FHIR Condition Diff

> CYCLE-16-C, Phase 4 Pilot Closing-the-Loop Thin-Slice. Sub-3 path-1
> deliverable. Captures the before-and-after state for the auto-correctable
> path: HL7v2 ADT^A01 with an ICD-9 diagnosis (DG1 segment, system="I9")
> transformed to a US-Core-compliant FHIR Condition with ICD-10-CM coding.
>
> Driver: `lithrim-command-center/.lithrim/prompts/c16c_phase4_pilot_closing_loop_driver.md`
> (commit `bd87d4b` on lithrim-command-center main).
>
> Sub-2 commit on lithrim-backend mvp-ready: `d44ca2e`. Mapping 44
> (`c16c-hl7-adt-to-fhir-condition-icd10-v1`) was POSTed to etlp-mapper
> Postgres in Sub-2; Sub-3 here exercises the apply round-trip and
> captures the diff.

## Files

| File | Purpose |
|---|---|
| `path1_before.json` | Synthesised baseline FHIR Condition. Represents what an HL7-to-Condition transformer using ICD-9 system as-is would emit. NOT produced by an actual mapping; constructed inline from the parsed DG1 segment (no transformation step). Anchors the demo's "before audit" snapshot. |
| `path1_after.json` | FHIR Condition produced by live `POST /mappings/44/apply` on the curated path-1 input. Carries `code.coding[0].system = http://hl7.org/fhir/sid/icd-10-cm` and `code = I10` after the Copilot-generated `$switch` lookup. |
| `path1_apply_capture.json` | Raw API response from `POST /mappings/44/apply` (includes the `result` key, `org/id`, etc). The literal evidence of the live round-trip. |
| `path1_diff.md` | Human-readable narrative of the before-and-after delta with US-Core Condition profile references. |
| `reparse.sh` | Self-contained reproducer. Runs from this directory or repo root, no setup beyond services-up (etlp-mapper on :3031, jq on PATH). Reproduces `path1_apply_capture.json` and `path1_after.json` from scratch. |

## How to reproduce

Pre-conditions: etlp-mapper running on `:3031`, mapping id 44 still
present, `jq` on PATH.

```
cd docs/research/c16c_reparse_diff
bash reparse.sh
```

Output:
- `path1_apply_capture.json` (raw response, overwritten)
- `path1_after.json` (extracted FHIR Condition, overwritten)
- HALT (c) verdict printed to stdout

If mapping 44 is missing (e.g. etlp-mapper Postgres was wiped), regenerate
from the lithrim-backend cache at
`scripts/phase4_pilot/cached_generated_template_path1.json` via
`PYENV_VERSION=debuglithrim pyenv exec python -m scripts.phase4_pilot.run path1`
(re-fires `/generate` and re-POSTs to `/mappings`; ~$0.01 LLM cost).

## API quirk encoded here

`POST /mappings/{id}/apply` does NOT auto-wrap `data` as
`{:resource <data>}`. `POST /mappings/test-template` and
`POST /mappings/generate` DO auto-wrap. Templates emitted by /generate
read paths like `$ resource.DG1.0.code_code.code`; for /apply to evaluate
those paths the caller must pre-wrap explicitly:

```
curl -X POST :3031/mappings/44/apply \
  -d '{"data": {"resource": <parsed_inner>}}'
```

This is encoded in `reparse.sh` and was discovered empirically during
Sub-3 (test-template auto-wrap returned the expected I10 code; /apply
without pre-wrap returned `code = null` and `display = null`).

## US-Core anchor

US-Core Condition Encounter Diagnosis profile
(`http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-encounter-diagnosis`)
binds `code.coding.system` to a value-set that includes
`http://hl7.org/fhir/sid/icd-10-cm` and SNOMED CT, but NOT
`http://hl7.org/fhir/sid/icd-9-cm`. The before-state therefore fails
US-Core; the after-state passes (Sub-4 verifies via `/v1/analyze`
re-audit).

## Cross-references

- Sub-1 REPORT: `lithrim-command-center/docs/research/REPORT_C16C_pattern_selection.md`
- Sub-2 cache: `lithrim-backend/scripts/phase4_pilot/cached_generated_template_path1.json`
- Sub-2 commit: `d44ca2e` on lithrim-backend mvp-ready
- Sub-3 path-2 deliverable (path-2 review-queue persist): see lithrim-backend `docs/research/c16c_path2_review_queue/REPORT.md` (lands in Commit B)
