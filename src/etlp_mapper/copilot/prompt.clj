(ns etlp-mapper.copilot.prompt
  "Prompt construction for the Jute Copilot.

   Builds:
   - a system prompt with the curated Jute DSL reference, convention rules,
     and three real few-shot examples (fetched from the production validators
     that ship with etlp-mapper)
   - the initial user prompt (sample input + expected output + description)
   - retry prompts with error or diff context"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Curated DSL reference. ~2K tokens of the essentials. Notes reflect Jute
;; 0.2.0-SNAPSHOT's ACTUAL behavior which differs from the formal spec:
;;   - String literals MUST be double-quoted. Single quotes fail to parse.
;;   - In YAML, an expression starting with '$ ' is a plain scalar and does
;;     not need YAML quoting — the real templates write `status: $ resource.x`
;;     without wrapping in \"...\".
;; ---------------------------------------------------------------------------

(def ^:private dsl-reference
  "Jute is a JSON/YAML template language for data transformation. Templates
are JSON/YAML documents where string values starting with '$ ' (dollar-space)
are evaluated as expressions against an input scope. Objects with directive
keys ($if, $map, $let, ...) are control structures. All other values pass
through.

EXPRESSION SYNTAX
- Path navigation:  $ object.key.nestedKey    (dot-separated; missing -> null)
- Array indexing:   $ arr.0  |  $ arr.-1       (negative counts from end)
- Wildcard:         $ arr.*.field              (expand over all elements)
- Deep wildcard:    $ root.**.field            (recursive)
- Predicate filter: $ coll.*(this.price < 10)  ('this' = current element)
- Dynamic path:     $ obj.(varExpression)
- Root reference:   $ @                        (full scope)
- Pipe:             $ x |> fn1(a) |> fn2(b)    (x becomes first arg of fn)
- Fn call in path:  $ splitStr(s, \" \").0

OPERATORS (standard precedence)
  ^   *  /  %   +  -   >  <  >=  <=   =  !=   &&   ||   !
  Equality is STRICT: true != \"True\". '+' on strings concatenates.
  null is first-class: $ foo = null works. Missing paths -> null.
  Only false and null are falsy — 0 and empty string are truthy.

STRING LITERALS
  Use DOUBLE QUOTES inside expressions: $ x = \"Patient\".
  Single-quoted literals FAIL to compile. This is the #1 rule; violate it
  and nothing will parse.

YAML QUOTING
  Expressions starting with '$ ' are plain YAML scalars and do not need to
  be wrapped in YAML quotes:
    actual: $ resource.resourceType              # correct, unquoted
    status: $ resource.gender                    # correct, unquoted
  You only need YAML quotes when the value contains YAML-significant chars
  (colons followed by space, '#', leading '[', etc.). Prefer unquoted.

DIRECTIVES

$if — conditional
  Full form:  {$if: <cond>, $then: <a>, $else: <b>}
  Short form: {$if: <cond>, key1: v1, key2: v2}
  YAML:
    status:
      $if: $ resource.gender
      $then: pass
      $else: fail

$map — iterate; returns an array
  $map: $ items
  $as: item
  $body: ...
  Or with index: $as: [item, idx]
  Or over an object: each element bound as {key: ..., value: ...}

$reduce — fold
  $reduce: $ items
  $as: [acc, item]
  $start: 0
  $body: $ acc + item.amount
  ($from is an alias for $start)

$let — local bindings
  Object form: {$let: {x: <expr>, y: <expr>}, $body: <template>}
  Array form:  {$let: [{a: <expr>}, {b: <uses a>}], $body: <template>}
  Widely used in real validators to name sub-results and aggregate them.

$fn / $call — functions
  {$fn: [x, y], $name: add, $body: \"$ x + y\"} — $name enables recursion.
  {$call: joinStr, $args: [\", \", \"$ items\"]}

$switch — pattern match on stringified value
  $switch: $ foo
  valueA: resultA
  valueB: resultB
  $default: fallback

BUILT-IN FUNCTIONS
  Strings:   joinStr(sep, arr), splitStr(s, re, [limit]), substr(s, start, [end]),
             replace(s, re, repl), toLowerCase(s), toUpperCase(s), capitalize(s),
             trim(s), str(v), toString(v)
  Arrays:    len(c), uniq(a), flatten(a), concat(a, b), groupBy(a, fn)
  Objects:   merge(m1, m2, ...), assoc(m, k, v)
  Types:     toInt(v), toDec(v), toKeyword(s)
  Math:      abs(n), range(end) | range(start, end) | range(start, end, step)
  Utility:   now(), daysInMonth(y, m), dropBlanks(v), hash(v), println(v)

CRITICAL CONVENTIONS FOR THIS CODEBASE
- Input data is ALWAYS wrapped as {resource: <data>} before the template runs.
- Access input fields via: $ resource.fieldName
- Output RAW YAML only. No markdown fences, no prose, no comments.
- Templates must be deterministic — never use randNth, now, hash, println.
- Match the shape of the expected output EXACTLY — same key names, same
  types, same nesting. If expected_output has `total_checks: 4` as a YAML
  number, produce a YAML number, not an expression that evaluates to 4.
")

;; ---------------------------------------------------------------------------
;; Spec addendum — rendered from resources/etlp_mapper/jute_dsl_spec.json.
;; Keeps the copilot prompt in lock-step with what /jute-dsl-spec.json serves
;; to external clients. Single source of truth for YAML-integration rules
;; and antipatterns, so drift can't reintroduce the ternary / unquoted-`:`
;; regressions that broke /mappings/generate output.
;; ---------------------------------------------------------------------------

(defn- render-yaml-integration [yaml]
  (let [chars (->> (:must_quote_whole_scalar_when_expression_contains yaml)
                   (map pr-str)
                   (str/join " "))]
    (str "YAML INTEGRATION\n"
         (:overview yaml) "\n"
         "- Leave unquoted only when the expression body contains: "
         (:unquoted_safe_body_chars yaml) ".\n"
         "- Double-quote the whole YAML scalar when the expression contains any of: "
         chars ".\n"
         "- " (:inside_double_quoted_yaml_note yaml) "\n"
         "Examples:\n"
         (str/join "\n"
                   (for [ex (:examples yaml)]
                     (str "  [" (:kind ex) "] " (:yaml ex)
                          "\n    -> " (:note ex)))))))

(defn- render-antipatterns [antis]
  (str "ANTIPATTERNS (do NOT do these)\n"
       (str/join "\n\n"
                 (for [a antis]
                   (str "- " (:name a) ":\n"
                        "    " (:description a)
                        (when-let [w (:wrong_example_yaml a)]
                          (str "\n    Wrong:\n      "
                               (str/replace w "\n" "\n      ")))
                        (when-let [r (:right_example_yaml a)]
                          (str "\n    Right:\n      "
                               (str/replace r "\n" "\n      ")))
                        (when-let [w (:wrong_example a)]
                          (str "\n    Wrong:  " w))
                        (when-let [r (:right_example a)]
                          (str "\n    Right:  " r)))))))

(defn- load-spec-addendum
  "Read yaml_integration and antipatterns from the exposed Jute DSL spec and
   render them as prose for the system prompt. Fails fast if the resource
   is missing — these rules are required context for the copilot."
  []
  (let [spec (-> (io/resource "etlp_mapper/jute_dsl_spec.json")
                 slurp
                 (json/parse-string true)
                 :jute_dsl_spec)]
    (str (render-yaml-integration (:yaml_integration spec))
         "\n\n"
         (render-antipatterns (:antipatterns spec)))))

(def ^:private spec-addendum (load-spec-addendum))

;; ---------------------------------------------------------------------------
;; Real few-shots pulled from production validators (ids 18, 26, 31). These
;; are live templates that compile and run on etlp-mapper — they're the
;; ground truth for what valid Jute looks like.
;; ---------------------------------------------------------------------------

(def ^:private patient-fewshot-yaml
  "# FHIR Patient Resource Validator
# Input: {resource: <FHIR Patient>} | Output: Validation result
resourceType: ValidationResult
profile: \"http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient\"
input_resource: $ resource.resourceType
checks:
  resource_type:
    field: resourceType
    expected: Patient
    actual: $ resource.resourceType
    status:
      $if: $ resource.resourceType = \"Patient\"
      $then: pass
      $else: fail
    message:
      $if: $ resource.resourceType = \"Patient\"
      $then: \"resourceType is Patient\"
      $else: \"Expected Patient, got unexpected resourceType\"
  has_identifier:
    field: identifier
    status:
      $if: $ len(resource.identifier) > 0
      $then: pass
      $else: fail
    message:
      $if: $ len(resource.identifier) > 0
      $then: \"Identifier(s) present\"
      $else: \"MISSING required identifier array\"
  has_family_name:
    field: name.family
    status:
      $if: $ resource.name.0.family
      $then: pass
      $else: fail
    message:
      $if: $ resource.name.0.family
      $then: \"Family name present\"
      $else: \"MISSING required family name\"
  has_gender:
    field: gender
    status:
      $if: $ resource.gender
      $then: pass
      $else: fail
    message:
      $if: $ resource.gender
      $then: \"Gender field present\"
      $else: \"MISSING required gender\"
  has_birth_date:
    field: birthDate
    status:
      $if: $ resource.birthDate
      $then: pass
      $else: fail
    message:
      $if: $ resource.birthDate
      $then: \"Birth date present\"
      $else: \"MISSING birthDate field\"
")

(def ^:private siu-fewshot-yaml
  "# HL7 SIU^S12 scheduling validator — uses $let/$reduce to aggregate checks
$let:
  c1:
    $if: $ resource.MSH
    $then:
      name: has-msh
      field: MSH
      status: pass
      message: MSH segment present
    $else:
      name: has-msh
      field: MSH
      status: fail
      message: Missing MSH segment
  c2:
    $if: $ resource.SCH
    $then:
      name: has-sch
      field: SCH
      status: pass
      message: SCH scheduling segment present
    $else:
      name: has-sch
      field: SCH
      status: fail
      message: Missing SCH scheduling segment
  c3:
    $if: $ resource.PID
    $then:
      name: has-pid
      field: PID
      status: pass
      message: PID segment present
    $else:
      name: has-pid
      field: PID
      status: fail
      message: Missing PID segment
$body:
  $let:
    checks:
      - $ c1
      - $ c2
      - $ c3
  $body:
    $let:
      passedChecks:
        $reduce: $ checks
        $as: [acc, c]
        $start: 0
        $body:
          $if: $ c.status = \"pass\"
          $then: $ acc + 1
          $else: $ acc
    $body:
      request:
        valid: $ passedChecks = 3
        resourceType: SIU-S12
        totalChecks: 3
        passedChecks: $ passedChecks
        failedChecks: $ 3 - passedChecks
        checks: $ checks
")

(def ^:private intake-fewshot-yaml
  "# Custom domain validator (non-FHIR) — patient intake form
$let:
  c1:
    $if: $ resource.patient
    $then:
      name: has-patient-name
      field: patient
      status: pass
      message: Patient name present
    $else:
      name: has-patient-name
      field: patient
      status: fail
      message: Missing patient name
  c2:
    $if: $ resource.dob
    $then:
      name: has-dob
      field: dob
      status: pass
      message: Date of birth present
    $else:
      name: has-dob
      field: dob
      status: fail
      message: Missing date of birth
  c3:
    $if: $ resource.consent
    $then:
      name: has-consent
      field: consent
      status: pass
      message: Consent recorded
    $else:
      name: has-consent
      field: consent
      status: fail
      message: Missing consent status
$body:
  $let:
    checks:
      - $ c1
      - $ c2
      - $ c3
  $body:
    $let:
      passedChecks:
        $reduce: $ checks
        $as: [acc, c]
        $start: 0
        $body:
          $if: $ c.status = \"pass\"
          $then: $ acc + 1
          $else: $ acc
    $body:
      request:
        valid: $ passedChecks = 3
        resourceType: IntakeRegistration
        totalChecks: 3
        passedChecks: $ passedChecks
        failedChecks: $ 3 - passedChecks
        checks: $ checks
")

(def ^:private hl7-to-analytical-fewshot-yaml
  "# HL7v2 ADT -> flat analytical record (pattern distilled from production id=4).
# Shows: top-level $fn helpers composed with merge() in $body, $map over
# repeating HL7 segments to build row collections, $switch as inline code
# lookup for enum normalization, substr-based date formatting.
# Naming: generic 'analytical_record' resource type, long snake_case column
# names (the production template uses shorthand codes like ST/EMPI/PSA1 —
# the copilot should prefer human-readable names unless the user asks for shorthands).
body:
  $let:
    normalize_gender:
      $fn: [val]
      $body:
        $switch: $ val
        M: male
        F: female
        U: unknown
        $default: unknown
    format_date:
      $fn: [t]
      $body:
        $if: $ len(t) >= 8
        $then: $ substr(t, 0, 4) + \"-\" + substr(t, 4, 6) + \"-\" + substr(t, 6, 8)
        $else: $ t
    patient_info:
      $fn: [msg]
      $body:
        patient_identifier: $ msg.PID.identifiers.0.value
        enterprise_master_id: $ msg.empi
        last_name: $ msg.PID.name.0.family.surname
        first_name: $ msg.PID.name.0.given
        date_of_birth: $ format_date(msg.PID.birth_date.time)
        gender: $ normalize_gender(msg.PID.gender)
        marital_status: $ msg.PID.marital_status.code
        city: $ msg.PID.address.0.city
        state: $ msg.PID.address.0.state
        postal_code: $ msg.PID.address.0.postal_code
    visit_info:
      $fn: [msg]
      $body:
        encounter_id: $ msg.PV1.visit_number.value
        patient_class: $ msg.PV1.patient_class
        admission_date: $ format_date(msg.PV1.admit_datetime.time)
        discharge_date: $ format_date(msg.PV1.discharge_datetime.time)
        admit_source: $ msg.PV1.admit_source
    providers:
      $fn: [msg]
      $body:
        attending_providers:
          $map: $ msg.PV1.attending_doctor
          $as: doc
          $body:
            provider_id: $ doc.id
            provider_name: $ doc.family.surname + \", \" + doc.given
        referring_providers:
          $map: $ msg.PV1.referring_doctor
          $as: doc
          $body:
            provider_id: $ doc.id
            provider_name: $ doc.family.surname + \", \" + doc.given
    diagnoses:
      $fn: [msg]
      $body:
        $map: $ msg.DG1
        $as: dg
        $body:
          diagnosis_code: $ dg.code_code.code
          diagnosis_name: $ dg.code_code.display
          code_system: $ dg.code_system
  $body: $ merge(patient_info(resource), visit_info(resource), providers(resource), {diagnoses: diagnoses(resource)})
resourceType: analytical_record
id: hl7_analytical_mapper
")

(def ^:private hl7-to-fhir-fewshot-yaml
  "# HL7v2 ADT -> FHIR Bundle transformation (trimmed from production id=3).
# Shows: $fn helpers in $let, composition via concat() in $body, $switch as
# inline code lookup, HL7 datetime -> ISO 8601 via substr + string concat.
body:
  type: transaction
  entry:
    $let:
      header:
        $fn: [msg]
        $body:
          - request:
              url: $ \"/fhir/MessageHeader/\" + msg.MSH.id
              method: PUT
            resource:
              resourceType: MessageHeader
              id: $ msg.MSH.id
              eventCoding: $ msg.MSH.type.event
              source: $ msg.MSH.app.ns
              destination:
                name: $ msg.MSH.receiving_app
                url: $ msg.MSH.receiving_network_address || msg.MSH.receiving_facility
      patient:
        $fn: [msg]
        $body:
          - request:
              url: $ \"/fhir/Patient/\" + msg.empi
              method: PUT
            resource:
              resourceType: Patient
              id: $ msg.empi
              name:
                - given:
                    - $ msg.PID.name.0.given
                  family: $ msg.PID.name.0.family.surname
              gender:
                M: male
                F: female
                U: unknown
                $switch: $ msg.PID.gender
                $default: unknown
              deceasedBoolean:
                N: false
                Y: true
                $switch: $ msg.PID.death_indicator
                $default: false
      format_hl7_datetime:
        $fn: [t]
        $body:
          $if: $ len(t) >= 14
          $then: $ substr(t, 0, 4) + \"-\" + substr(t, 4, 6) + \"-\" + substr(t, 6, 8) + \"T\" + substr(t, 8, 10) + \":\" + substr(t, 10, 12) + \":\" + substr(t, 12, 14) + \"Z\"
          $else: $ t
      visit:
        $fn: [msg]
        $body:
          $if: $ msg.PV1
          $then:
            - request:
                url: /fhir/Encounter
                method: POST
              resource:
                resourceType: Encounter
                class:
                  code: $ msg.PV1.patient_class
                subject:
                  reference: $ \"Patient/\" + msg.empi
                period:
                  start: $ format_hl7_datetime(msg.PV1.admit_datetime.time)
                status:
                  $if: $ msg.PV1.discharge_datetime.time
                  $then: finished
                  $else: in-progress
    $body: $ concat(header(resource), patient(resource), visit(resource))
  resourceType: Bundle
")

(def ^:private default-few-shots
  [{:description "FHIR Patient validator — flat checks map with per-field status + message."
    :sample-input {:resource {:resourceType "Patient"
                              :identifier [{:system "urn:oid:2.16.840.1.113883.4.1"
                                            :value "784-1234-5678901"}]
                              :name [{:family "Doe" :given ["Jane"]}]
                              :gender "female"
                              :birthDate "1985-03-12"}}
    :template patient-fewshot-yaml}

   {:description "HL7 SIU^S12 scheduling validator — $let/$reduce aggregation pattern."
    :sample-input {:resource {:MSH {:type {:code "SIU" :event "S12"}}
                              :SCH {:appointment_type "CONSULT"}
                              :PID {:identifiers [{:value "12345"}]}}}
    :template siu-fewshot-yaml}

   {:description "Custom non-FHIR validator — patient intake form with $let/$reduce aggregation."
    :sample-input {:resource {:patient "Jane Doe"
                              :dob "1985-03-12"
                              :consent "signed"}}
    :template intake-fewshot-yaml}

   {:description (str "HL7v2 ADT -> FHIR Bundle transformation (not a validator). "
                      "Shows top-level $fn helpers composed via concat(), "
                      "$switch used as an inline code lookup map, and "
                      "HL7 datetime string formatting via substr + \"+\".")
    :sample-input {:resource {:empi "784-1234-5678901"
                              :MSH {:id "MSG001"
                                    :type {:event "A04"}
                                    :app {:ns "HOSP-ADT"}
                                    :receiving_app "MALAFFI"
                                    :receiving_facility "AUH"}
                              :PID {:gender "M"
                                    :death_indicator "N"
                                    :name [{:given "Ahmed"
                                            :family {:surname "Al Maktoum"}}]}
                              :PV1 {:patient_class "I"
                                    :admit_datetime {:time "20260415120000"}
                                    :discharge_datetime {:time nil}}}}
    :template hl7-to-fhir-fewshot-yaml}

   {:description (str "HL7v2 ADT -> flat analytical record. Shows $fn helpers "
                      "composed with merge() in $body, $map over repeating "
                      "HL7 segments, $switch as inline code lookup. Use long "
                      "snake_case column names in output by default.")
    :sample-input {:resource {:empi "EMP-001"
                              :PID {:identifiers [{:value "784-1234-5678901"}]
                                    :name [{:given "Ahmed"
                                            :family {:surname "Al Maktoum"}}]
                                    :birth_date {:time "19900515"}
                                    :gender "M"
                                    :marital_status {:code "M"}
                                    :address [{:city "Abu Dhabi"
                                               :state "AZ"
                                               :postal_code "00000"}]}
                              :PV1 {:visit_number {:value "V-123"}
                                    :patient_class "I"
                                    :admit_datetime {:time "20260415"}
                                    :discharge_datetime {:time "20260417"}
                                    :admit_source "6"
                                    :attending_doctor [{:id "DR001"
                                                        :family {:surname "Khan"}
                                                        :given "Omar"}]
                                    :referring_doctor []}
                              :DG1 [{:code_code {:code "I10"
                                                 :display "Essential hypertension"}
                                     :code_system "ICD-10"}]}}
    :template hl7-to-analytical-fewshot-yaml}])

;; ---------------------------------------------------------------------------
;; Assembly
;; ---------------------------------------------------------------------------

(defn- pretty-json [v]
  (json/generate-string v {:pretty true}))

(defn- format-few-shot [idx {:keys [description sample-input template]}]
  (str "Example " (inc idx) ": " description "\n"
       "Wrapped input (this is what the template sees):\n"
       (pretty-json sample-input) "\n"
       "Template (YAML):\n"
       template))

;; ---------------------------------------------------------------------------
;; Shape summary — Tier 1 of Phase 4 Copilot improvements (2026-04-17).
;;
;; Walks sample_input and emits a flat, readable list of accessible field
;; paths with their types. Feeds into the user prompt so the LLM picks
;; concrete real paths (e.g. $ resource.PID.identifiers.0.value) instead
;; of guessing from the description text (which often uses wrong names
;; like PID.3.id because the author doesn't know the parser's schema).
;;
;; For HL7-parsed JSON this is crucial: the parser uses long snake_case
;; names (PID.identifiers[0].value, PV1.assigned_patient_location.facility.ns)
;; that no one knows off the top of their head. Dumping the full JSON into
;; the prompt works but buries the paths in value noise; a condensed path
;; table makes them salient.
;; ---------------------------------------------------------------------------

(defn- value-type-label
  "Compact type label for a leaf value in a shape summary."
  [v]
  (cond
    (nil? v)              "null"
    (boolean? v)          "boolean"
    (number? v)           (if (integer? v) "integer" "number")
    (string? v)           (str "string" (when (> (count v) 60)
                                           (str " (" (count v) " chars)")))
    (keyword? v)          "string"
    :else                 "value"))

(defn- describe-shape*
  "Recursive helper. Accumulates [path, type-label] pairs into `acc`.
   Depth-limited; arrays are probed into their first element only
   (representative sampling). Paths are dotted, 1-indexed-safe for use
   in Jute expressions (`$ resource.key.0.nested`)."
  [acc path value depth max-depth max-entries]
  (cond
    ;; Cap on output size — avoids the prompt ballooning on huge inputs.
    (>= (count acc) max-entries)
    acc

    (>= depth max-depth)
    (conj acc [path (cond
                      (map? value)        (str "object (" (count value) " keys)")
                      (sequential? value) (str "array[" (count value) "] (truncated)")
                      :else               (value-type-label value))])

    (map? value)
    (reduce (fn [a [k v]]
              (describe-shape* a (str path "." (name k)) v (inc depth) max-depth max-entries))
            (conj acc [path (str "object (" (count value) " keys)")])
            value)

    (sequential? value)
    (let [len (count value)
          acc' (conj acc [path (str "array[" len "]"
                                     (cond
                                       (zero? len) " (empty)"
                                       (= 1 len)   ""
                                       :else       " (showing index 0)"))])]
      (if (pos? len)
        (describe-shape* acc' (str path ".0") (first value) (inc depth) max-depth max-entries)
        acc'))

    :else
    (conj acc [path (value-type-label value)])))

(defn- format-shape-lines
  "Format [path, type] pairs as aligned `path: type` lines. Keeps the
   column aligned for visual scanning. Column width capped at 50 chars
   so very deep paths wrap gracefully."
  [pairs]
  (let [max-path-len (min 50 (->> pairs (map (comp count first)) (apply max 0)))
        pad           (fn [s] (let [pad-n (- max-path-len (count s))]
                                (if (pos? pad-n) (str s (apply str (repeat pad-n \space))) s)))]
    (str/join "\n" (map (fn [[p t]] (str "  " (pad p) "  " t)) pairs))))

(defn describe-shape
  "Public entry point. Produces a multi-line shape summary for a sample
   input value. The root path defaults to `resource` to match the
   `{resource: <sample>}` wrapping convention used everywhere in the
   Copilot. Depth and entry caps prevent huge samples from bloating the
   prompt.

   Called only for the user prompt (not system prompt) because it's
   per-call, small, and the system prompt already carries the DSL ref
   + few-shots."
  ([sample-input]
   (describe-shape sample-input {:max-depth 4 :max-entries 80}))
  ([sample-input {:keys [max-depth max-entries]
                  :or {max-depth 4 max-entries 80}}]
   (let [pairs     (describe-shape* [] "resource" sample-input 0 max-depth max-entries)
         truncated? (>= (count pairs) max-entries)]
     (str (format-shape-lines pairs)
          (when truncated?
            (str "\n  … (truncated at " max-entries " paths; "
                 "max-depth=" max-depth ")"))))))

(defn build-system-prompt
  "Assemble the system prompt. Pass custom `few-shots` to override defaults."
  ([]
   (build-system-prompt default-few-shots))
  ([few-shots]
   (str "You are a Jute DSL expert. You generate YAML transformation and "
        "validation templates for healthcare data (FHIR, HL7v2, custom JSON).\n\n"
        "=== DSL REFERENCE ===\n\n"
        dsl-reference
        "\n=== YAML INTEGRATION & ANTIPATTERNS ===\n"
        "(Mirrored from the Jute DSL spec served at GET /jute-dsl-spec.json — "
        "external clients see the same rules.)\n\n"
        spec-addendum
        "\n\n=== FEW-SHOT EXAMPLES (REAL production templates) ===\n\n"
        (str/join "\n\n" (map-indexed format-few-shot few-shots))
        "\n\n=== OUTPUT FORMAT ===\n"
        "Respond with ONLY the Jute template as raw YAML. No markdown code "
        "fences. No prose. No comments outside the template. The YAML must "
        "parse with a standard YAML parser and compile with jute.core/compile. "
        "Remember: DOUBLE QUOTES for string literals, NEVER single quotes.\n")))

(defn build-user-prompt
  "Assemble the initial user message describing what to generate.

   Tier 1 improvement (2026-04-17): injects a shape summary of
   sample_input between the description and the full JSON dump. The
   summary enumerates every accessible path (up to depth 4 / 80 entries)
   with its type, so the LLM has a concise menu of valid paths instead
   of having to rediscover them inside the JSON. This is the fix for the
   Phase 4 finding where users wrote wrong field paths in descriptions
   (e.g. `PID.3.id` instead of `PID.identifiers[0].value`) and the LLM
   followed the description path literally."
  [{:keys [sample-input expected-output description source-format target-platform]}]
  (str "Generate a Jute template that transforms the sample input into the "
       "expected output.\n\n"
       (when source-format   (str "Source format: " source-format "\n"))
       (when target-platform (str "Target platform: " target-platform "\n"))
       "\nDescription:\n" description "\n"
       "\n=== SAMPLE INPUT SHAPE ===\n"
       "(Reference these exact paths in your template via $ <path>. "
       "Treat this as authoritative: if the description refers to a "
       "path that is NOT in this table, the description is wrong — use "
       "the path from the table that best matches the intent.)\n\n"
       (describe-shape sample-input) "\n"
       "\n=== FULL SAMPLE INPUT (wrapped as the template will see it) ===\n"
       (pretty-json {:resource sample-input}) "\n"
       "\n=== EXPECTED OUTPUT (match this shape and values EXACTLY) ===\n"
       (pretty-json expected-output) "\n"
       "\nReturn ONLY the YAML template."))

;; ---------------------------------------------------------------------------
;; Merge mode — extending an existing template with a new section.
;;
;; Used when the user opens AI Assist on a mapping that already has content
;; in the editor and wants to add a new section without losing the existing
;; $let bindings or $body assembly. See etlp-mapper.copilot.engine/generate
;; and the plan at ~/.claude/plans/unified-percolating-anchor.md for context.
;; ---------------------------------------------------------------------------

(defn build-merge-system-prompt
  "System prompt for merge mode. Same DSL reference and few-shots as the
   normal system prompt, plus strict rules instructing the LLM to preserve
   the existing template verbatim and add new sections alongside rather
   than replacing anything."
  ([]
   (build-merge-system-prompt default-few-shots))
  ([few-shots]
   (str "You are a Jute DSL expert extending an EXISTING template with a "
        "new section.\n\n"
        "=== DSL REFERENCE ===\n\n"
        dsl-reference
        "\n=== YAML INTEGRATION & ANTIPATTERNS ===\n"
        "(Mirrored from the Jute DSL spec served at GET /jute-dsl-spec.json — "
        "external clients see the same rules.)\n\n"
        spec-addendum
        "\n\n=== FEW-SHOT EXAMPLES (REAL production templates) ===\n\n"
        (str/join "\n\n" (map-indexed format-few-shot few-shots))
        "\n\n=== EXTEND MODE RULES ===\n\n"
        "You will be given an EXISTING Jute template followed by a NEW "
        "SECTION SPEC. Your job: return a single complete template that is "
        "the existing template PLUS the new section, merged cleanly.\n\n"
        "Non-negotiable rules:\n"
        "1. PRESERVE every line of the existing template's $let bindings "
        "verbatim. Do not rename, remove, or modify existing $fn helpers, "
        "bindings, or fields. If the existing template has `format_date`, "
        "your output must still have `format_date` with the exact same body.\n"
        "2. ADD new $fn helpers alongside existing ones in the same $let "
        "block. Do not wrap the whole thing in a new outer $let.\n"
        "3. EXTEND the existing $body to include the new section's output "
        "fields. If the existing $body uses merge(), append your new "
        "section to it. If it's a flat map, add your new fields to it.\n"
        "4. The provided sample_input + expected_output test ONLY the new "
        "section. Existing sections have their own tests and must still "
        "work — do not remove or change them just because they don't "
        "appear in the new expected_output.\n"
        "5. Return the FULL merged template as YAML. No markdown code "
        "fences. No prose. No comments outside the template.\n\n"
        "Remember: DOUBLE QUOTES for string literals, NEVER single quotes.\n")))

(defn build-merge-user-prompt
  "User message for merge mode. Includes the existing template in a clearly
   labelled block, then the new section spec (same shape as build-user-prompt).

   Tier 1 improvement (2026-04-17): same shape summary injection as
   build-user-prompt. Especially valuable in merge mode because the user
   is adding a new section to a template that already references many
   paths — the summary makes it obvious which paths are available without
   forcing the LLM to re-derive them from the existing template."
  [{:keys [existing-template sample-input expected-output description
           source-format target-platform]}]
  (str "Extend the following existing template with a new section.\n\n"
       "=== EXISTING TEMPLATE ===\n"
       existing-template
       "\n=== END EXISTING TEMPLATE ===\n\n"
       "=== NEW SECTION SPEC ===\n"
       (when source-format   (str "Source format: " source-format "\n"))
       (when target-platform (str "Target platform: " target-platform "\n"))
       "\nDescription of the new section:\n" description "\n"
       "\n=== SAMPLE INPUT SHAPE ===\n"
       "(Reference these exact paths in the new section via $ <path>. "
       "If the description names a path that isn't here, use the "
       "closest match from the table instead.)\n\n"
       (describe-shape sample-input) "\n"
       "\n=== FULL SAMPLE INPUT (wrapped as the template will see it) ===\n"
       (pretty-json {:resource sample-input}) "\n"
       "\n=== EXPECTED OUTPUT FOR THE NEW SECTION ONLY ===\n"
       "(the existing sections have their own test data and must still work)\n"
       (pretty-json expected-output) "\n"
       "\nReturn the FULL MERGED template as YAML. Preserve the existing "
       "template verbatim and add the new section alongside it."))

(defn build-retry-prompt
  "Assemble a retry message when a previous attempt failed or mismatched.

   When `:merge-mode?` is true, the mismatch branch adds explicit guidance
   about the Jute scope trap (new fields ending up inside $if/$then/$else
   branches where they become unreachable). This is the #1 failure mode
   for merge mode at large template scale (id=3-class), and the generic
   'output did not match' diff wasn't actionable — retries just burned
   tokens without fixing placement. See Phase 2.6 plan."
  [{:keys [attempt-number actual-output expected-output diff error merge-mode?]}]
  (cond
    error
    (str "Attempt " attempt-number " failed to parse or compile.\n"
         "Error: " error "\n"
         "Common causes: (1) using single quotes for string literals — use "
         "DOUBLE quotes only, e.g. $ x = \"Patient\"; (2) YAML indentation; "
         "(3) directive keys missing $-prefix.\n"
         "Fix the template. Return ONLY the corrected YAML.")

    merge-mode?
    (str "Attempt " attempt-number " of the merge compiled but the new "
         "section isn't appearing in the output.\n\n"
         "The most common cause is a Jute SCOPE TRAP: you added the new "
         "fields inside a $then or $else branch of an existing $if "
         "directive. Jute evaluates $if and returns ONE branch — any "
         "sibling fields outside the chosen branch are silently dropped.\n\n"
         "Check the existing template's $body structure:\n"
         "  - If $body is a flat map: add new fields as top-level siblings "
         "of existing fields.\n"
         "  - If $body uses $if/$then/$else: add new fields OUTSIDE all "
         "branches, at the same indentation level as the $if directive "
         "itself — OR duplicate them inside BOTH $then and $else so they "
         "are always emitted regardless of which branch runs.\n"
         "  - If $body uses merge(a, b, c): extend the merge call with a "
         "new map containing the new fields: merge(a, b, c, {new_field: ...}).\n\n"
         "Missing or mismatched in the output:\n"
         (or diff "(no diff available — verify the expected keys exist at the right scope)")
         "\n\nReminder: the expected output describes ONLY the new section. "
         "The existing sections produce additional fields in the output; "
         "that's EXPECTED and does NOT mean they need to be removed from "
         "the template. Do not delete any existing $fn helpers, $let "
         "bindings, or $body sections.\n\n"
         "Fix the placement and return the FULL merged template as YAML.")

    :else
    (str "Attempt " attempt-number " compiled but produced the wrong output.\n\n"
         "Your previous template produced:\n"
         (pretty-json actual-output) "\n"
         "\nExpected:\n"
         (pretty-json expected-output) "\n"
         (when diff (str "\nDifferences: " diff "\n"))
         "\nFix the template to produce the expected output exactly. "
         "Return ONLY the corrected YAML template.")))
