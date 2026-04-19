(ns etlp-mapper.handler.copilot
  "HTTP handlers for the Jute Copilot endpoints.

   - POST /mappings/generate — generate/merge a Jute template with the LLM
   - POST /mappings/test-template — compile + apply a template against a
     sample input without persisting anything to the DB. Used by the AI
     Merge flow's inline 'Test against samples' panel.

   Both responses use the ataraxy response-vector convention so they flow
   through the same JSON serialization middleware as the rest of the API.

   See docs/specs/SPEC_jute_copilot.md and
   ~/.claude/plans/unified-percolating-anchor.md for the design."
  (:require [ataraxy.response :as response]
            [clojure.string :as str]
            [integrant.core :as ig]
            [etlp-mapper.copilot.engine :as engine]))

(defn- nil-if-blank [s]
  (when-not (str/blank? s) s))

(defn- validate-request
  "Returns nil if body is valid, else a short field name describing what's missing."
  [body]
  (cond
    (nil? body)                                 "body"
    (nil? (:sample_input body))                 "sample_input"
    (nil? (:expected_output body))              "expected_output"
    (or (nil? (:description body))
        (not (string? (:description body)))
        (str/blank? (:description body)))       "description"
    :else nil))

(defn- build-llm-config
  "Resolve provider-specific config from the integrant init map. Empty strings
   become nil so the client raises :llm-unavailable when a key is missing,
   rather than failing at system init."
  [{:keys [llm-provider llm-model
           azure-endpoint azure-api-key
           anthropic-api-key]}]
  (case (keyword llm-provider)
    :azure     {:provider "azure"
                :model    llm-model
                :endpoint (nil-if-blank azure-endpoint)
                :api-key  (nil-if-blank azure-api-key)}
    :anthropic {:provider "anthropic"
                :model    llm-model
                :api-key  (nil-if-blank anthropic-api-key)}
    (throw (ex-info (str "Unknown COPILOT_LLM_PROVIDER: " llm-provider)
                    {:type :invalid-config}))))

(defn- handle-ex-info [e]
  ;; Note: ataraxy 0.4.2 doesn't define ::unprocessable-entity or ::bad-gateway.
  ;; ::service-unavailable is mapped to status 502 in that version, which is
  ;; what we want for upstream LLM failures — so we use it here.
  (let [{:keys [type provider status]} (ex-data e)
        msg (.getMessage e)]
    (println (str "[copilot/error] " type " provider=" provider " status=" status " msg=" msg))
    (case type
      :llm-unavailable
      [::response/service-unavailable {:error "llm_unavailable"
                                       :provider (some-> provider name)
                                       :detail msg}]
      :llm-error
      [::response/service-unavailable {:error "llm_error"
                                       :provider (some-> provider name)
                                       :upstream_status status
                                       :detail msg}]
      :invalid-config
      [::response/internal-server-error {:error "invalid_config" :detail msg}]
      ;; default
      [::response/internal-server-error {:error "internal_error" :detail msg}])))

(defn- count-fn-definitions
  "Rough but cheap: count occurrences of `$fn:` at the start of a YAML-ish
   line. Used as a post-generation sanity check in merge mode — if the
   returned template dropped any `$fn` helpers from the existing template,
   we flag it as a warning (non-blocking; the user can regenerate)."
  [yaml-text]
  (if (str/blank? yaml-text)
    0
    (count (re-seq #"(?m)^\s*\$fn\s*:" yaml-text))))

(defmethod ig/init-key :etlp-mapper.handler.copilot/generate
  [_ config]
  (let [llm-config (build-llm-config config)]
    (fn [{:keys [body-params identity] :as _request}]
      (let [extend? (not (str/blank? (:existing_template body-params)))]
        (println (str "[copilot/request] org=" (:org/id identity)
                      " mode=" (if extend? "extend" "new")
                      " has-sample=" (some? (:sample_input body-params))
                      " desc-len=" (count (or (:description body-params) ""))
                      (when extend?
                        (str " existing-len=" (count (:existing_template body-params)))))))
      (if-let [missing (validate-request body-params)]
        [::response/bad-request {:error "invalid_input"
                                 :detail (str "Missing or invalid field: " missing)}]
        (try
          (let [{:keys [sample_input expected_output description
                        source_format target_platform existing_template]} body-params
                result (engine/generate
                        llm-config
                        {:sample-input      sample_input
                         :expected-output   expected_output
                         :description       description
                         :source-format     source_format
                         :target-platform   target_platform
                         :existing-template existing_template})
                ;; Merge-mode sanity check: did the LLM drop any $fn helpers?
                warning (when (and (not (str/blank? existing_template))
                                   (:template result))
                          (let [before (count-fn-definitions existing_template)
                                after  (count-fn-definitions (:template result))]
                            (when (< after before)
                              (str "Merged template has " after " $fn helpers "
                                   "but the existing template had " before
                                   ". The LLM may have dropped " (- before after)
                                   " helper(s). Review before saving."))))
                result-with-warning (cond-> result
                                      warning (assoc-in [:test_result :warning] warning))]
            (println (str "[copilot/done] confidence=" (:confidence result)
                          " retries=" (:retries_used result)
                          " tokens=" (-> result :metadata :tokens_used)
                          (when warning " WARNING-fn-count-dropped")))
            ;; ataraxy 0.4.2 has no ::unprocessable-entity (422). Map "failed"
            ;; to ::bad-request (400) and keep the semantic error code in the body.
            (if (= "failed" (:confidence result))
              [::response/bad-request (assoc result-with-warning :error "generation_failed")]
              [::response/ok result-with-warning]))
          (catch clojure.lang.ExceptionInfo e
            (handle-ex-info e))
          (catch Exception e
            (println (str "[copilot/unexpected] " (.getMessage e)))
            [::response/internal-server-error {:error "internal_error"
                                               :detail (.getMessage e)}]))))))

;; ---------------------------------------------------------------------------
;; POST /mappings/test-template
;;
;; Compile + apply a template against a sample_input entirely in-memory.
;; No DB. Designed for the AI Merge inline-test panel: the user can verify
;; a generated merged template against multiple samples before committing
;; it to the DataMapper editor (which is still separate from a DB save).
;;
;; Request:  { template: "<yaml>", sample_input: <any> }
;; Response: { compiled: bool, output: any|null, error: string|null }
;;
;; Always returns HTTP 200 on well-formed requests — a compile or apply
;; failure is reported as { compiled: false, error: "..." } so the UI
;; can display it inline without special-casing HTTP error codes.
;; ---------------------------------------------------------------------------

(defn- validate-test-template-request
  "Returns nil if body is valid, else a short field name describing what's missing."
  [body]
  (cond
    (nil? body)                                       "body"
    (or (nil? (:template body))
        (not (string? (:template body)))
        (str/blank? (:template body)))                "template"
    (nil? (:sample_input body))                       "sample_input"
    :else nil))

(defmethod ig/init-key :etlp-mapper.handler.copilot/test-template
  [_ _config]
  (fn [{:keys [body-params identity] :as _request}]
    (println (str "[copilot/test-template] org=" (:org/id identity)
                  " template-len=" (count (or (:template body-params) ""))
                  " has-sample=" (some? (:sample_input body-params))))
    (if-let [missing (validate-test-template-request body-params)]
      [::response/bad-request {:error "invalid_input"
                               :detail (str "Missing or invalid field: " missing)}]
      (try
        (let [{:keys [template sample_input]} body-params
              attempt (engine/try-once template sample_input)]
          (if (:ok attempt)
            [::response/ok {:compiled true
                            :output   (:output attempt)
                            :error    nil}]
            [::response/ok {:compiled false
                            :output   nil
                            :error    (:error attempt)}]))
        (catch Exception e
          (println (str "[copilot/test-template/unexpected] " (.getMessage e)))
          [::response/internal-server-error {:error "internal_error"
                                             :detail (.getMessage e)}])))))
