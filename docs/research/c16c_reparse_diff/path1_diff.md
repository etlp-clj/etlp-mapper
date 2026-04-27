# Path 1 Before/After FHIR Condition Diff

> Source input: HL7v2 ADT^A01 with `DG1|1||4019^Essential hypertension^I9|||F`.
> Parsed via `POST :3031/parse-hl7` 2026-04-27. Re-parsed via `POST :3031/mappings/44/apply` (mapping id 44, `c16c-hl7-adt-to-fhir-condition-icd10-v1`, Sub-2 commit `d44ca2e` on lithrim-backend mvp-ready).

## Field-by-field delta

| Path | Before (synthesised) | After (live /apply) | Driver |
|---|---|---|---|
| `resourceType` | `Condition` | `Condition` | (unchanged) |
| `code.coding[0].system` | `http://hl7.org/fhir/sid/icd-9-cm` | `http://hl7.org/fhir/sid/icd-10-cm` | static value in template |
| `code.coding[0].code` | `4019` | `I10` | `$switch` lookup `4019 -> I10` |
| `code.coding[0].display` | `Essential hypertension` | `Essential hypertension` | passthrough from `resource.DG1.0.code_code.display` |

The system URI shift is the load-bearing change for US-Core compliance.
The code value shift `4019 -> I10` is the lookup table entry exercised
by this fixture; the same template handles `25000 -> E11.9` (type-2
diabetes) and `7140 -> M05.79` (rheumatoid arthritis) in the lookup
table that Sub-4 may expand-test if budget permits.

## US-Core profile reference

- US-Core Condition Encounter Diagnosis
  (`http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-encounter-diagnosis`)
  binds `Condition.code` to the value-set
  `http://hl7.org/fhir/us/core/ValueSet/us-core-condition-code`.
- That value-set includes ICD-10-CM and SNOMED CT for diagnosis codes;
  it does NOT include ICD-9-CM.
- Before-state therefore fails the binding. After-state passes.

## Audit interpretation (forward-looking, Sub-4 territory)

Sub-4's `/v1/analyze` on the before-state Condition is expected to
return a council finding referencing US-Core `code` element binding
violation. Sub-4's `/v1/analyze` on the after-state Condition is
expected to return clean (no US-Core code-system finding). The verdict
shift from reject to approve on the same logical patient input is the
demo's "auto-corrected" arc.

## What did NOT change

- The display string `"Essential hypertension"` is unchanged because
  ICD-9 4019 and ICD-10-CM I10 both map to the same human-readable
  description (per CMS GEMs).
- All other artifact fields are absent in both before and after; the
  thin-slice scope is the diagnosis coding, not the full FHIR Condition
  resource (subject reference, recordedDate, encounter reference,
  category, etc would be added in productionization).
- The HL7v2 input itself is unchanged. Sub-3 does not modify the raw
  input; re-parse is on the SAME input, demonstrating the architecture
  works without source-side correction.
