#!/usr/bin/env bash
# C16-C Sub-3 path-1 reproducer.
#
# Re-parses the curated HL7v2 ADT^A01 input via /parse-hl7, applies
# mapping 44 (c16c-hl7-adt-to-fhir-condition-icd10-v1, the
# Copilot-generated ICD-9-to-ICD-10-CM transformer landed in
# Sub-2 commit d44ca2e on lithrim-backend mvp-ready), and captures
# the FHIR Condition output.
#
# Pre-conditions:
#   etlp-mapper running on http://localhost:3031
#   mapping id 44 present (verify via: curl :3031/mappings/44)
#   jq on PATH
#
# Usage:
#   bash reparse.sh
#
# Outputs (overwritten in this directory):
#   path1_apply_capture.json   raw API response from POST /mappings/44/apply
#   path1_after.json           extracted FHIR Condition (just the result key)
#
# Exit code: 0 on success, 1 if HALT (c) gate fails.

set -euo pipefail

cd "$(dirname "$0")"

# Path-1 raw HL7v2 ADT^A01 input (verbatim from
# lithrim-backend/demo_dataset/integration_scenarios/c16c/path1_icd9_in_dg1.hl7;
# segments separated by CR per HL7v2 standard).
RAW_HL7=$'MSH|^~\\&|EPIC|EH|RECV|FAC|20260427120000||ADT^A01|MSG_C16C_P1|P|2.5\rEVN|A01|20260427120000\rPID|1||MRN_C16C_001^^^EHR^MR||DOE^JOHN^A||19800515|M|||1 MAIN ST^^BOSTON^MA^02101\rPV1|1|I|2W^201^A\rDG1|1||4019^Essential hypertension^I9|||F'

echo "step 1: POST /parse-hl7"
PARSE_RESP=$(curl -sS -X POST http://localhost:3031/parse-hl7 \
  -H 'Content-Type: application/json' \
  --data "$(jq -n --arg msg "$RAW_HL7" '{message: $msg}')")

INNER=$(echo "$PARSE_RESP" | jq '.parsed')

# Quick sanity on the parser output.
DG1_SYSTEM=$(echo "$INNER" | jq -r '.DG1[0].code_code.system')
DG1_CODE=$(echo "$INNER" | jq -r '.DG1[0].code_code.code')
echo "  parsed DG1.0.code_code.system = $DG1_SYSTEM"
echo "  parsed DG1.0.code_code.code   = $DG1_CODE"

echo ""
echo "step 2: POST /mappings/44/apply"
echo "  note: /apply does NOT auto-wrap data; pre-wrapping as {resource: <inner>}"
echo "        so the template paths like \$ resource.DG1.0.code_code.code resolve."
APPLY_RESP=$(curl -sS -X POST http://localhost:3031/mappings/44/apply \
  -H 'Content-Type: application/json' \
  --data "$(jq -n --argjson inner "$INNER" '{data: {resource: $inner}}')")

echo "$APPLY_RESP" | jq '.' > path1_apply_capture.json
echo "  wrote path1_apply_capture.json"

echo "$APPLY_RESP" | jq '.result' > path1_after.json
echo "  wrote path1_after.json"

echo ""
echo "verification:"
SYSTEM=$(echo "$APPLY_RESP" | jq -r '.result.code.coding[0].system')
CODE=$(echo "$APPLY_RESP" | jq -r '.result.code.coding[0].code')
DISPLAY=$(echo "$APPLY_RESP" | jq -r '.result.code.coding[0].display')
echo "  code.coding[0].system  = $SYSTEM"
echo "  code.coding[0].code    = $CODE"
echo "  code.coding[0].display = $DISPLAY"

if [ "$SYSTEM" = "http://hl7.org/fhir/sid/icd-10-cm" ] && [ "$CODE" = "I10" ]; then
    echo ""
    echo "HALT (c) gate: PASS"
    exit 0
else
    echo ""
    echo "HALT (c) gate: FAIL"
    echo "  expected system=http://hl7.org/fhir/sid/icd-10-cm code=I10"
    exit 1
fi
