(ns etlp-mapper.mapping-operations-test
  (:require [ataraxy.response :as response]
            [clojure.test :refer :all]
            [ring.util.http-response :as http]
            [etlp-mapper.auth :as auth]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.handler.mappings :as mappings]))

(defn create-handler [req]
  (http/ok {:org/id (get-in req [:identity :org/id])}))


(deftest mapping-create-requires-role
  (let [app ((auth/require-role :editor) create-handler)
        resp (app {:identity {:org/id "org-1" :roles #{:editor}}})]
    (is (= 200 (:status resp)))
    (is (= "org-1" (get-in resp [:body :org/id])))))

(deftest mapping-create-role-forbidden
  (let [app ((auth/require-role :editor) create-handler)
        resp (app {:identity {:org/id "org-1" :roles #{:admin}}})]
    (is (= 403 (:status resp)))))

(deftest mapping-lifecycle-logs
  (let [audit (atom [])
        ai (atom [])
        db {}
        org-id "org-1"
        user-id (str (java.util.UUID/randomUUID))
        create-h (ig/init-key :etlp-mapper.handler.mappings/create {:db db
                                                                    :handler (fn [_] [::response/created {:id 1}])})
        update-h (ig/init-key :etlp-mapper.handler.mappings/update {:db db
                                                                    :handler (fn [_] [::response/no-content nil])})
        destroy-h (ig/init-key :etlp-mapper.handler.mappings/destroy {:db db
                                                                      :handler (fn [_] [::response/no-content nil])})
        apply-h (ig/init-key :etlp-mapper.handler/apply-mappings {:db db})]
    (with-redefs [audit-logs/log! (fn [_ data] (swap! audit conj data))
                  ai-usage-logs/log! (fn [_ data] (swap! ai conj data))
                  mappings/apply-mapping (fn [_ _ _ _] {:ok true})]
      (create-h {:identity {:org/id org-id :claims {:sub user-id}}
                 :ataraxy/result [::any "Example" {:content true}]})
      (update-h {:identity {:org/id org-id :claims {:sub user-id}}
                 :ataraxy/result [::any 1 {:content true}]})
      (apply-h {:ataraxy/result [::any 1 {}]
                :identity {:org/id org-id :claims {:sub user-id}}})
      (destroy-h {:identity {:org/id org-id :claims {:sub user-id}}
                  :ataraxy/result [::any 1]})
      (let [audit-events @audit
            ai-events @ai]
        (is (= 4 (count audit-events)))
        (is (= ["create-mapping" "update-mapping" "apply-mapping" "destroy-mapping"]
               (map :action audit-events)))
        (is (= ["mapping-create" "mapping-update" "transform" "mapping-delete"]
               (map :feature-type ai-events)))
        (is (= [org-id org-id org-id org-id]
               (map :org-id audit-events)))
        (is (= [user-id user-id user-id user-id]
               (map :user-id audit-events)))
        (is (= "Example" (get-in audit-events [0 :context :title])))
        (is (= 1 (get-in audit-events [1 :context :mapping-id])))
        (is (= {:mapping-id 1} (get-in audit-events [3 :context])))
        (is (every? zero? (map :input-tokens ai-events)))
        (is (every? zero? (map :output-tokens ai-events)))))))

