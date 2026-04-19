(ns etlp-mapper.llm.client
  "Model-agnostic LLM client. Supports Azure OpenAI and Anthropic via a single
   `generate-completion` entry point.

   Config shape:
     {:provider  \"azure\" | \"anthropic\"
      :model     \"gpt-4.1-mini\" | \"claude-sonnet-4-5-...\"
      :endpoint  (azure only) full deployment chat-completions URL
      :api-key   provider API key}

   Returns:
     {:text          string
      :tokens-used   long (or nil)
      :model         string
      :finish-reason string}

   Throws ex-info on error with :type one of
     :llm-unavailable | :llm-error | :invalid-config."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]))

;; 180s default. Merge mode can regenerate a 26KB template (id=3-class) which
;; takes ~60-90s of output-token streaming; the previous 60s timeout was too
;; tight for that path. Non-merge calls finish in 3-5s so the larger budget
;; is only a fallback.
(def ^:private default-timeout-ms 180000)

(defn- parse-json [s]
  (try (json/parse-string s true) (catch Exception _ nil)))

(defn- azure-completion
  [{:keys [endpoint api-key]} messages {:keys [temperature max-tokens]}]
  (when (str/blank? endpoint)
    (throw (ex-info "AZURE_OPENAI_ENDPOINT not configured"
                    {:type :llm-unavailable :provider :azure})))
  (when (str/blank? api-key)
    (throw (ex-info "AZURE_OPENAI_API_KEY not configured"
                    {:type :llm-unavailable :provider :azure})))
  (let [body     {:messages    messages
                  :temperature (or temperature 0.2)
                  :max_tokens  (or max-tokens 4096)}
        response (try
                   (http/post endpoint
                              {:headers            {"api-key"      api-key
                                                    "Content-Type" "application/json"}
                               :body               (json/generate-string body)
                               :socket-timeout     default-timeout-ms
                               :connection-timeout default-timeout-ms
                               :throw-exceptions   false
                               :as                 :string})
                   (catch Exception e
                     (throw (ex-info (str "Azure OpenAI request failed: " (.getMessage e))
                                     {:type :llm-unavailable :provider :azure}
                                     e))))
        status   (:status response)
        parsed   (parse-json (:body response))]
    (cond
      (nil? status)
      (throw (ex-info "Azure OpenAI returned no response"
                      {:type :llm-unavailable :provider :azure}))
      (>= status 500)
      (throw (ex-info (str "Azure OpenAI server error " status)
                      {:type :llm-unavailable :provider :azure
                       :status status :body (:body response)}))
      (>= status 400)
      (throw (ex-info (str "Azure OpenAI client error " status ": "
                           (or (some-> parsed :error :message) (:body response)))
                      {:type :llm-error :provider :azure
                       :status status :body (:body response)})))
    (let [choice (-> parsed :choices first)
          msg    (:message choice)]
      {:text          (:content msg)
       :tokens-used   (-> parsed :usage :total_tokens)
       :model         (:model parsed)
       :finish-reason (:finish_reason choice)})))

(defn- anthropic-completion
  [{:keys [api-key model]} messages {:keys [temperature max-tokens]}]
  (when (str/blank? api-key)
    (throw (ex-info "ANTHROPIC_API_KEY not configured"
                    {:type :llm-unavailable :provider :anthropic})))
  (let [system-text (->> messages
                         (filter #(= "system" (:role %)))
                         (map :content)
                         (str/join "\n\n"))
        other-msgs  (vec (remove #(= "system" (:role %)) messages))
        body        (cond-> {:model       model
                             :messages    other-msgs
                             :temperature (or temperature 0.2)
                             :max_tokens  (or max-tokens 4096)}
                      (not (str/blank? system-text)) (assoc :system system-text))
        response    (try
                      (http/post "https://api.anthropic.com/v1/messages"
                                 {:headers            {"x-api-key"         api-key
                                                       "anthropic-version" "2023-06-01"
                                                       "Content-Type"      "application/json"}
                                  :body               (json/generate-string body)
                                  :socket-timeout     default-timeout-ms
                                  :connection-timeout default-timeout-ms
                                  :throw-exceptions   false
                                  :as                 :string})
                      (catch Exception e
                        (throw (ex-info (str "Anthropic request failed: " (.getMessage e))
                                        {:type :llm-unavailable :provider :anthropic}
                                        e))))
        status      (:status response)
        parsed      (parse-json (:body response))]
    (cond
      (nil? status)
      (throw (ex-info "Anthropic returned no response"
                      {:type :llm-unavailable :provider :anthropic}))
      (>= status 500)
      (throw (ex-info (str "Anthropic server error " status)
                      {:type :llm-unavailable :provider :anthropic
                       :status status :body (:body response)}))
      (>= status 400)
      (throw (ex-info (str "Anthropic client error " status ": "
                           (or (some-> parsed :error :message) (:body response)))
                      {:type :llm-error :provider :anthropic
                       :status status :body (:body response)})))
    (let [text  (->> (:content parsed)
                     (filter #(= "text" (:type %)))
                     (map :text)
                     (str/join ""))
          usage (:usage parsed)]
      {:text          text
       :tokens-used   (+ (or (:input_tokens usage) 0)
                         (or (:output_tokens usage) 0))
       :model         (:model parsed)
       :finish-reason (:stop_reason parsed)})))

(defn generate-completion
  "Call the configured LLM provider. `config` is the provider config map.
   `messages` is an OpenAI-style message vector [{:role ... :content ...}].
   `opts` may include :temperature and :max-tokens."
  ([config messages] (generate-completion config messages {}))
  ([{:keys [provider] :as config} messages opts]
   (case (keyword provider)
     :azure     (azure-completion config messages opts)
     :anthropic (anthropic-completion config messages opts)
     (throw (ex-info (str "Unknown COPILOT_LLM_PROVIDER: " provider)
                     {:type :invalid-config :provider provider})))))
