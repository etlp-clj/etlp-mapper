(ns etlp-mapper.onboarding-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.handler.orgs]
            [etlp-mapper.handler.invites]
            [etlp-mapper.handler.me]
            [etlp-mapper.onboarding :as onboarding]))

(deftest onboarding-flow-logs
  (let [audit (atom [])
        ai (atom [])
        db {}
        user-id (str (java.util.UUID/randomUUID))
        create-org (ig/init-key :etlp-mapper.handler.orgs/create {:db db :kc {}})
        invite-create (ig/init-key :etlp-mapper.handler.invites/create {:db db :token {:app-secret "s"}})
        invite-accept (ig/init-key :etlp-mapper.handler.invites/accept {:db db :token {:app-secret "s"}})
        set-active (ig/init-key :etlp-mapper.handler.me/set-active-org {:db db})]
    (with-redefs [audit-logs/log! (fn [_ data] (swap! audit conj data))
                  ai-usage-logs/log! (fn [_ data] (swap! ai conj data))
                  onboarding/ensure-org! (fn [_ _ _] "org-1")
                  jdbc/query (fn [_ _] [{:organization_id "org-1"}])
                  jdbc/update! (fn [& _] nil)]
      (let [[_ {:keys [org_id]}] (create-org {:body-params {:name "Acme"}
                                              :identity {:claims {:sub user-id}}})]
        (invite-create {:ataraxy/result [::any org_id]
                        :body-params {:email "user@example.com"}
                        :identity {:claims {:sub user-id :roles #{:owner}}}})
        (invite-accept {:body-params {:token "t" :org_id org_id}
                        :identity {:claims {:sub user-id}}})
        (set-active {:body-params {:org_id org_id}
                     :identity {:claims {:sub user-id}}})
        (let [audit-events @audit
              ai-events @ai]
          (is (= 4 (count audit-events)))
          (is (= 4 (count ai-events)))
          (is (= [org_id org_id org_id org_id]
                 (map :org-id audit-events)))
          (is (= [user-id user-id user-id user-id]
                 (map :user-id audit-events)))
          (is (= ["create-organization" "create-invite" "accept-invite" "set-active-org"]
                 (map :action audit-events)))
          (is (string? (get-in audit-events [1 :context :token])))
          (is (= "t" (get-in audit-events [2 :context :token])))
          (is (= ["onboarding" "invite" "invite" "active-org"]
                 (map :feature-type ai-events)))
          (is (every? zero? (map :input-tokens ai-events)))
          (is (every? zero? (map :output-tokens ai-events))))))))
