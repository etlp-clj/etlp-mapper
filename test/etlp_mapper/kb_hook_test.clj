(ns etlp-mapper.kb-hook-test
  "Unit tests for the KB CDC hook client + middleware.

  Validates:
  * Event resolution from (request, response) — correctly extracts
    mapping-id, org-id, action across POST/PUT/DELETE variants.
  * Non-2xx and non-mapping requests produce no event.
  * fire-mapping-changed is fire-and-forget — exceptions from the HTTP
    call do not propagate to the caller.
  * The middleware returns the downstream response unchanged."
  (:require
   [clojure.test :refer [deftest is testing]]
   [etlp-mapper.kb-hook :as hook]))

;; ── Event resolution ────────────────────────────────────────────────────

(deftest resolves-create-event-from-location-header
  (let [req  {:request-method :post
              :uri "/mappings"
              :identity {:org/id "org-alpha"}}
        resp {:status 201
              :headers {"Location" "/mappings/42"}}
        event (#'hook/resolve-mapping-event req resp)]
    (is (= {:mapping-id 42 :org-id "org-alpha" :action "insert"} event))))

(deftest resolves-update-event-from-path
  (let [req  {:request-method :put
              :uri "/mappings/99"
              :identity {:org/id "org-beta"}}
        resp {:status 200}
        event (#'hook/resolve-mapping-event req resp)]
    (is (= {:mapping-id 99 :org-id "org-beta" :action "update"} event))))

(deftest resolves-delete-event-from-path
  (let [req  {:request-method :delete
              :uri "/mappings/7"
              :identity {:org/id "org-gamma"}}
        resp {:status 204}
        event (#'hook/resolve-mapping-event req resp)]
    (is (= {:mapping-id 7 :org-id "org-gamma" :action "delete"} event))))

(deftest ignores-get-requests
  (let [req  {:request-method :get
              :uri "/mappings/1"
              :identity {:org/id "org-x"}}
        resp {:status 200}]
    (is (nil? (#'hook/resolve-mapping-event req resp)))))

(deftest ignores-non-mapping-paths
  (let [req  {:request-method :post
              :uri "/parse-hl7"
              :identity {:org/id "org-x"}}
        resp {:status 200}]
    (is (nil? (#'hook/resolve-mapping-event req resp)))))

(deftest ignores-failed-responses
  (let [req  {:request-method :put
              :uri "/mappings/5"
              :identity {:org/id "org-x"}}
        resp {:status 400}]
    (is (nil? (#'hook/resolve-mapping-event req resp)))))

(deftest ignores-create-without-location-header
  ;; POST /mappings returns 201 but no Location header — we can't know
  ;; the new mapping id, so we skip the hook rather than guess.
  (let [req  {:request-method :post
              :uri "/mappings"
              :identity {:org/id "org-x"}}
        resp {:status 201 :headers {}}]
    (is (nil? (#'hook/resolve-mapping-event req resp)))))

(deftest ignores-request-without-org-id
  ;; Auth middleware failed to attach identity — skip rather than guess.
  (let [req  {:request-method :put
              :uri "/mappings/42"
              :identity {}}
        resp {:status 200}]
    (is (nil? (#'hook/resolve-mapping-event req resp)))))

;; ── fire-mapping-changed — fire-and-forget semantics ────────────────────

(deftest disabled-client-returns-nil-without-call
  (let [f (hook/fire-mapping-changed {:enabled? false :url "http://x" :secret "s"}
                                     {:mapping-id 1 :org-id "x" :action "update"})]
    (is (nil? f))))

(deftest missing-url-returns-nil
  (let [f (hook/fire-mapping-changed {:url "" :secret "s"}
                                     {:mapping-id 1 :org-id "x" :action "update"})]
    (is (nil? f))))

(deftest missing-secret-returns-nil
  (let [f (hook/fire-mapping-changed {:url "http://x" :secret nil}
                                     {:mapping-id 1 :org-id "x" :action "update"})]
    (is (nil? f))))

(deftest http-exceptions-are-swallowed
  ;; Stub clj-http.client/post to throw. The future deref returns the
  ;; exception-handler's result (nil from log/warn) — crucially it does
  ;; NOT re-raise into the caller.
  (with-redefs [clj-http.client/post (fn [& _] (throw (ex-info "boom" {})))]
    (let [f (hook/fire-mapping-changed
              {:url "http://x/hook" :secret "s" :timeout-ms 100}
              {:mapping-id 1 :org-id "org-x" :action "insert"})]
      (is (future? f))
      ;; Deref completes without re-raising — the catch block in the
      ;; future caught the exception and returned nil.
      (is (nil? @f)))))

;; ── Middleware ──────────────────────────────────────────────────────────

(deftest middleware-returns-downstream-response-unchanged
  (let [downstream (fn [_] {:status 201 :headers {"Location" "/mappings/88"} :body "ok"})
        wrapped    (hook/wrap-mapping-change-hook downstream {:enabled? false :url "" :secret ""})
        resp       (wrapped {:request-method :post
                             :uri "/mappings"
                             :identity {:org/id "org-z"}})]
    (is (= 201 (:status resp)))
    (is (= "ok" (:body resp)))
    (is (= "/mappings/88" (get-in resp [:headers "Location"])))))

(deftest middleware-passes-through-non-mapping-requests
  (let [downstream (fn [_] {:status 200 :body "whoami"})
        calls      (atom 0)
        client     {:url "http://x"
                    :secret "s"
                    :enabled? true}]
    (with-redefs [clj-http.client/post (fn [& _] (swap! calls inc) {:status 202})]
      (let [wrapped (hook/wrap-mapping-change-hook downstream client)
            resp    (wrapped {:request-method :get :uri "/whoami"})]
        (is (= 200 (:status resp)))
        ;; No hook fired for unrelated paths.
        (is (zero? @calls))))))

(deftest middleware-fires-hook-on-mapping-create
  (let [downstream (fn [_] {:status 201 :headers {"Location" "/mappings/500"} :body nil})
        calls      (atom [])]
    (with-redefs [clj-http.client/post (fn [url opts]
                                          (swap! calls conj {:url url :opts opts})
                                          {:status 202})]
      (let [wrapped (hook/wrap-mapping-change-hook
                      downstream
                      {:url "http://kb-consumer/hook" :secret "S" :enabled? true})
            _resp (wrapped {:request-method :post
                            :uri "/mappings"
                            :identity {:org/id "org-fire"}})]
        ;; Let the future run.
        (Thread/sleep 100)
        (is (= 1 (count @calls)))
        (let [{:keys [url opts]} (first @calls)]
          (is (= "http://kb-consumer/hook" url))
          (is (= "S" (get-in opts [:headers "X-ETLP-Hook-Secret"])))
          (is (re-find #"500" (:body opts)))
          (is (re-find #"insert" (:body opts)))
          (is (re-find #"org-fire" (:body opts))))))))
