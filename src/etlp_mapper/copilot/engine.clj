(ns etlp-mapper.copilot.engine
  "Generate -> test -> refine loop for the Jute Copilot.

   `generate` takes an LLM config and a validated user request, constructs
   the prompt, calls the LLM, compiles+applies the template against the
   sample input, compares the output with the expected output, and retries
   up to `max-retries` times with accumulated error context.

   Returns a response map with the final template, test result, confidence,
   retries used, and metadata."
  (:require [clojure.string :as str]
            [yaml.core :as yaml]
            [jute.core :as jt]
            [etlp-mapper.copilot.prompt :as prompt]
            [etlp-mapper.llm.client :as llm]))

(def ^:private max-retries 2)  ;; 3 attempts total: 0 (initial) + 2 retries

;; ---------------------------------------------------------------------------
;; Response normalization — jute's output often contains keyword keys and
;; lazy seqs. Cheshire parsed the user's expected_output into keyword-keyed
;; maps and vectors. Normalize both sides to string-keyed maps + vectors so
;; `=` gives a fair comparison regardless of source.
;; ---------------------------------------------------------------------------

(defn- deep-jsonify [v]
  (cond
    (map? v)        (into {} (for [[k vv] v]
                               [(if (keyword? k) (name k) (str k))
                                (deep-jsonify vv)]))
    (sequential? v) (mapv deep-jsonify v)
    :else           v))

(defn- deep-subset?
  "True if every scalar/collection in `expected` exists at the same path in
   `actual` with a matching value. Used for merge mode where `actual` is a
   superset (existing sections + new section) and we only test the new
   section via the user-provided expected_output. Extra keys/elements in
   `actual` are ignored.

   Arrays use zip-by-index semantics: expected[i] must be a subset of
   actual[i] for every i in the expected array. actual can have more
   elements than expected; those are ignored."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (and (contains? actual k)
                   (deep-subset? v (get actual k))))
            expected)

    (and (sequential? expected) (sequential? actual))
    (and (<= (count expected) (count actual))
         (every? identity (map deep-subset? expected actual)))

    :else
    (= expected actual)))

(defn- output-matches?
  "Strict mode (default): expected must equal actual exactly, key-for-key.
   Subset mode: every key in expected must exist in actual at the same
   path with a matching value; actual may have additional keys. Subset
   mode is used in merge mode because the merged template legitimately
   produces the existing sections' output alongside the new section, and
   the user's expected_output only describes the new part."
  ([actual expected] (output-matches? actual expected :strict))
  ([actual expected match-mode]
   (let [a (deep-jsonify actual)
         e (deep-jsonify expected)]
     (case match-mode
       :strict (= a e)
       :subset (deep-subset? e a)))))

(defn- compute-diff
  "Short human-readable summary of where `actual` differs from `expected`
   under STRICT equality. Reports missing keys, extra keys, and value
   mismatches. Used by non-merge (new-template) mode on retries."
  [actual expected]
  (let [a (deep-jsonify actual)
        e (deep-jsonify expected)]
    (cond
      (= a e) nil

      (and (map? a) (map? e))
      (let [all-keys (distinct (concat (keys a) (keys e)))
            diffs (keep (fn [k]
                          (let [av (get a k ::missing)
                                ev (get e k ::missing)]
                            (cond
                              (= av ev)       nil
                              (= av ::missing) (str "missing key '" k "' (expected " (pr-str ev) ")")
                              (= ev ::missing) (str "unexpected key '" k "' (got " (pr-str av) ")")
                              :else            (str "key '" k "': got " (pr-str av)
                                                    ", expected " (pr-str ev)))))
                        all-keys)]
        (str/join "; " diffs))

      :else (str "got " (pr-str a) ", expected " (pr-str e)))))

(defn- compute-subset-diff
  "Walks `expected` and reports only the keys that are missing from `actual`
   or have a mismatched value. Ignores extra keys in `actual` — which is
   the whole point of merge-mode diffing: the merged template produces
   more output than the user's narrow expected_output describes, and we
   only want to surface what SHOULD be there but isn't.

   Used by merge mode on retries so the LLM sees actionable feedback like
   'missing clinical_summary at top level' instead of a 100-line deep diff."
  [expected actual]
  (let [e (deep-jsonify expected)
        a (deep-jsonify actual)]
    (letfn [(walk [exp act path]
              (cond
                (and (map? exp) (map? act))
                (mapcat (fn [[k v]]
                          (let [p (if (str/blank? path)
                                    (name (if (keyword? k) k (str k)))
                                    (str path "." (name (if (keyword? k) k (str k)))))
                                av (get act k ::missing)]
                            (cond
                              (= av ::missing) [(str "missing '" p "' (expected " (pr-str v) ")")]
                              (and (map? v) (map? av)) (walk v av p)
                              (and (sequential? v) (sequential? av)) (walk v av p)
                              (= v av) nil
                              :else [(str "'" p "': got " (pr-str av) ", expected " (pr-str v))])))
                        exp)

                (and (sequential? exp) (sequential? act))
                (mapcat (fn [i ev]
                          (let [p (str path "[" i "]")
                                av (nth act i ::missing)]
                            (cond
                              (= av ::missing) [(str "missing element at " p " (expected " (pr-str ev) ")")]
                              (and (map? ev) (map? av)) (walk ev av p)
                              (and (sequential? ev) (sequential? av)) (walk ev av p)
                              (= ev av) nil
                              :else [(str p ": got " (pr-str av) ", expected " (pr-str ev))])))
                        (range) exp)

                (= exp act) nil
                :else [(str path ": got " (pr-str act) ", expected " (pr-str exp))]))]
      (let [msgs (walk e a "")]
        (if (seq msgs)
          (str/join "; " msgs)
          nil)))))

;; ---------------------------------------------------------------------------
;; YAML extraction + compile + apply
;; ---------------------------------------------------------------------------

(defn- strip-fences
  "Strip ```yaml ... ``` or ``` ... ``` markdown fences if present."
  [text]
  (let [trimmed (str/trim (or text ""))]
    (if (str/starts-with? trimmed "```")
      (-> trimmed
          (str/replace #"(?s)\A```(?:yaml|yml)?\s*\n?" "")
          (str/replace #"(?s)\s*```\s*\z" "")
          str/trim)
      trimmed)))

(defn try-once
  "Parse YAML -> jt/compile -> apply to {:resource sample-input}.
   Returns {:ok true :output ...} on success, or
   {:ok false :error-kind ... :error ...} on failure.

   Public so the /mappings/test-template handler can reuse the same
   in-memory compile+apply path without duplicating logic or touching
   the database. See handler/copilot.clj."
  [yaml-text sample-input]
  (let [parsed (try
                 {:ok (yaml/parse-string yaml-text :keywords true)}
                 (catch Exception e {:err (.getMessage e) :kind :yaml-parse}))]
    (if-let [err (:err parsed)]
      {:ok false :error-kind :yaml-parse :error (str "YAML parse: " err)}
      (let [compiled (try
                       {:ok (jt/compile (:ok parsed))}
                       (catch Exception e {:err (.getMessage e) :kind :jute-compile}))]
        (if-let [err (:err compiled)]
          {:ok false :error-kind :jute-compile :error (str "Jute compile: " err)}
          (let [result (try
                         {:ok ((:ok compiled) {:resource sample-input})}
                         (catch Exception e {:err (.getMessage e) :kind :jute-apply}))]
            (if-let [err (:err result)]
              {:ok false :error-kind :jute-apply :error (str "Template execution: " err)}
              {:ok true :output (:ok result)})))))))

;; ---------------------------------------------------------------------------
;; Main loop
;; ---------------------------------------------------------------------------

(defn- confidence-for-attempt [attempt]
  (case (long attempt)
    0 "high"
    1 "medium"
    "low"))

(defn- build-metadata [started-ns tokens model provider]
  {:model              model
   :tokens_used        tokens
   :generation_time_ms (quot (- (System/nanoTime) started-ns) 1000000)
   :provider           (name (or (some-> provider keyword) :unknown))})

(defn generate
  "Run the generate -> test -> refine loop.

   `llm-config` — see etlp-mapper.llm.client
   `inputs`     — {:sample-input :expected-output :description
                   [:source-format] [:target-platform] [:existing-template]}

   When `:existing-template` is present, the engine switches to merge mode:
   a different system/user prompt that tells the LLM to preserve the
   existing template verbatim and add the new section alongside. The
   compile->apply->compare loop is unchanged — the new sample/expected
   tests only the new section; we trust the LLM (with the strict prompt)
   to leave the existing sections intact. Merge mode bumps max-tokens to
   8192 because merged templates can be long (id=3 is ~26KB)."
  [llm-config {:keys [sample-input expected-output existing-template] :as inputs}]
  (let [merge-mode?   (boolean (and existing-template
                                    (not (clojure.string/blank? existing-template))))
        match-mode    (if merge-mode? :subset :strict)
        system-prompt (if merge-mode?
                        (prompt/build-merge-system-prompt)
                        (prompt/build-system-prompt))
        user-prompt   (if merge-mode?
                        (prompt/build-merge-user-prompt inputs)
                        (prompt/build-user-prompt inputs))
        max-tokens    (if merge-mode? 8192 4096)
        ;; In merge mode, diffs report only missing/mismatched keys in
        ;; `expected` — extra keys in `actual` are legitimate (existing
        ;; sections keep producing output). In new-template mode we use
        ;; the full strict diff.
        diff-fn       (if merge-mode?
                        (fn [actual expected] (compute-subset-diff expected actual))
                        compute-diff)
        started-ns    (System/nanoTime)
        provider      (:provider llm-config)]
    (loop [attempt       0
           messages      [{:role "system" :content system-prompt}
                          {:role "user"   :content user-prompt}]
           total-tokens  0
           last-model    nil]
      (let [llm-resp   (llm/generate-completion llm-config messages
                                                {:temperature 0.2 :max-tokens max-tokens})
            yaml-text  (strip-fences (:text llm-resp))
            tokens     (+ total-tokens (or (:tokens-used llm-resp) 0))
            model      (or (:model llm-resp) last-model)
            attempt-rs (try-once yaml-text sample-input)
            matches?   (and (:ok attempt-rs)
                            (output-matches? (:output attempt-rs) expected-output match-mode))]
        (cond
          ;; Success
          matches?
          {:template     yaml-text
           :format       "yaml"
           :test_result  {:compiled         true
                          :output           (deep-jsonify (:output attempt-rs))
                          :matches_expected true
                          :diff             nil}
           :confidence   (confidence-for-attempt attempt)
           :retries_used attempt
           :metadata     (build-metadata started-ns tokens model provider)}

          ;; Out of retries — return what we have
          (>= attempt max-retries)
          (let [compiled? (:ok attempt-rs)
                diff      (when compiled? (diff-fn (:output attempt-rs) expected-output))]
            {:template     yaml-text
             :format       "yaml"
             :test_result  {:compiled         compiled?
                            :output           (when compiled?
                                                (deep-jsonify (:output attempt-rs)))
                            :matches_expected false
                            :diff             (or diff (:error attempt-rs))}
             :confidence   (if compiled? "partial" "failed")
             :retries_used attempt
             :metadata     (build-metadata started-ns tokens model provider)})

          ;; Retry: append assistant message + retry instruction
          :else
          (let [retry-msg (if (:ok attempt-rs)
                            (prompt/build-retry-prompt
                             {:attempt-number  (inc attempt)
                              :actual-output   (deep-jsonify (:output attempt-rs))
                              :expected-output expected-output
                              :diff            (diff-fn (:output attempt-rs) expected-output)
                              :merge-mode?     merge-mode?})
                            (prompt/build-retry-prompt
                             {:attempt-number (inc attempt)
                              :error          (:error attempt-rs)
                              :merge-mode?    merge-mode?}))]
            (recur (inc attempt)
                   (conj messages
                         {:role "assistant" :content yaml-text}
                         {:role "user"      :content retry-msg})
                   tokens
                   model)))))))
