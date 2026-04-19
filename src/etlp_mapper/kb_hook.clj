(ns etlp-mapper.kb-hook
  "CDC hook client for the lithrim-backend Template Pattern KB.

  Fires POST /v1/admin/kb/hooks/mapping-changed after any successful
  INSERT / UPDATE / DELETE on the mappings table. Fire-and-forget via
  `future` with a short timeout — mapping CRUD must never block on the
  KB side (SPEC §P2-6.6 graceful-degradation discipline).

  Auth: shared secret in the `X-ETLP-Hook-Secret` header. Lithrim compares
  with `hmac.compare_digest`. Both deployments rotate the secret in
  lockstep."
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [integrant.core :as ig]))

(defn- log-warn
  "Minimal structured-ish log. etlp-mapper handlers use println for
  operational events; match the local convention rather than pulling in
  a new logging dep for a single namespace."
  [event data]
  (println (str "[kb-hook][WARN] " event " " (pr-str data))))

(defn- log-debug
  [event data]
  ;; No-op by default; flip to println when debugging the hook locally.
  nil)

(def mapping-path-regex
  "Matches POST /mappings (create) and PUT|DELETE /mappings/{id}."
  #"^/mappings(?:/(\d+))?/?$")

(defn- location->mapping-id
  "Create responses set Location: /mappings/{id}. Extract the int id."
  [response]
  (when-let [loc (get-in response [:headers "Location"])]
    (when-let [match (re-matches #"/?mappings/(\d+)/?" loc)]
      (Integer/parseInt (second match)))))

(defn- method->action
  "HTTP method → CDC action label. Returns nil for methods we ignore."
  [method]
  (case method
    :post   "insert"
    :put    "update"
    :delete "delete"
    nil))

(defn- resolve-mapping-event
  "Inspect (request, response) and return a {:mapping-id :org-id :action}
  map if this round-trip represents a mapping mutation that finished
  successfully. Returns nil for every other request (GETs, failures,
  non-mappings paths, etc.).

  Only considers 2xx responses — 4xx/5xx means the SQL didn't land and
  there's nothing to reindex."
  [request response]
  (let [status (:status response)
        uri (:uri request)
        method (:request-method request)]
    (when (and status (<= 200 status 299)
               uri method
               (re-matches mapping-path-regex uri))
      (let [path-id (when-let [match (re-matches mapping-path-regex uri)]
                      (when (second match)
                        (Integer/parseInt (second match))))
            mapping-id (or path-id (location->mapping-id response))
            action (method->action method)
            org-id (get-in request [:identity :org/id])]
        (when (and mapping-id action org-id)
          {:mapping-id mapping-id
           :org-id     org-id
           :action     action})))))

(defn fire-mapping-changed
  "POST the mapping-changed event to lithrim. Fire-and-forget — any
  failure is logged (not re-raised) so the caller never observes the
  KB's availability. Returns the `future` so tests can deref to await."
  [{:keys [url secret enabled? timeout-ms]
    :or   {enabled? true timeout-ms 2000}}
   {:keys [mapping-id org-id action]}]
  (cond
    (not enabled?)
    (do (log-debug "kb-hook-disabled" {:mapping-id mapping-id :action action})
        nil)

    (or (nil? url) (empty? url))
    (do (log-warn "kb-hook-url-missing" {:mapping-id mapping-id :action action})
        nil)

    (or (nil? secret) (empty? secret))
    (do (log-warn "kb-hook-secret-missing" {:mapping-id mapping-id :action action})
        nil)

    :else
    (future
      (try
        (let [payload {:mapping_id mapping-id :org_id org-id :action action}
              resp    (http/post url
                                 {:body (json/generate-string payload)
                                  :headers {"X-ETLP-Hook-Secret" secret
                                            "Content-Type" "application/json"}
                                  :socket-timeout timeout-ms
                                  :connection-timeout timeout-ms
                                  :throw-exceptions false
                                  :as :string})]
          (if (<= 200 (:status resp 0) 299)
            (log-debug "kb-hook-accepted"
                       {:mapping-id mapping-id :action action :status (:status resp)})
            (log-warn "kb-hook-non-2xx"
                      {:mapping-id mapping-id :action action :status (:status resp)}))
          resp)
        (catch Exception e
          (log-warn "kb-hook-fire-failed"
                    {:mapping-id mapping-id :action action :error (.getMessage e)}))))))

(defn wrap-mapping-change-hook
  "Ring middleware: after a downstream successful mapping mutation, fires
  the CDC hook in a future. Responses are returned unchanged so callers
  never observe any KB-side latency."
  [handler client-opts]
  (fn [request]
    (let [response (handler request)]
      (when-let [event (resolve-mapping-event request response)]
        (fire-mapping-changed client-opts event))
      response)))

(defmethod ig/init-key :etlp-mapper.kb-hook/client [_ opts]
  "Integrant init — returns the client config map consumed by middleware."
  (println (str "[kb-hook][INFO] kb-hook-initialised "
                (pr-str {:enabled?    (:enabled? opts)
                         :url-set?    (boolean (seq (:url opts)))
                         :secret-set? (boolean (seq (:secret opts)))})))
  opts)

(defmethod ig/init-key :etlp-mapper.kb-hook/middleware [_ {:keys [client]}]
  "Integrant init — the actual ring middleware, parameterised by the client."
  (fn [handler]
    (wrap-mapping-change-hook handler client)))
