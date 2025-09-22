(ns etlp-mapper.invites-handler-test
  (:require [ataraxy.handler :as handler]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.config :as config]
            [etlp-mapper.handler.invites]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members]
            [etlp-mapper.test-support :refer [with-test-datasource]]))

(defn with-invite-secret [f]
  (with-redefs [config/invite-secret (constantly "test-secret")]
    (f)))

(use-fixtures :once with-test-datasource)
(use-fixtures :each with-invite-secret)

(deftest create-requires-admin
  (let [app (ig/init-key :etlp-mapper.handler.invites/create {:db {:spec ::db}
                                                              :token {}})
        resp (handler/sync-default {:ataraxy/result
                                    (app {:ataraxy/result [nil "org-1"]
                                          :body-params {:email "user@example.com"}
                                          :identity {:org/id "org-1"
                                                     :user {:id "u1"}
                                                     :roles #{:user}}})})]
    (is (= 403 (:status resp)))))

(deftest create-stores-invite
  (let [captured (atom nil)
        log-captured (atom nil)
        app (ig/init-key :etlp-mapper.handler.invites/create {:db {:spec ::db}
                                                               :token {}})]
    (with-redefs [org-invites/sign-token (fn [_ claims]
                                           (str "signed-" (:email claims)))
                  org-invites/upsert-invite (fn [_ data] (reset! captured data))
                  audit-logs/log! (fn [_ data] (reset! log-captured data))
                  ai-usage-logs/log! (fn [& _] nil)]
      (let [resp (handler/sync-default {:ataraxy/result
                                        (app {:ataraxy/result [nil "org-1"]
                                              :body-params {:email "user@example.com"
                                                            :role "mapper"}
                                              :identity {:org/id "org-1"
                                                         :user {:id "user-1"}
                                                         :roles #{:admin}}})})]
        (is (= 200 (:status resp)))
        (is (= {:token "signed-user@example.com"} (:body resp)))
        (is (= "org-1" (:organization_id @captured)))
        (is (= "user@example.com" (:email @captured)))
        (is (= "pending" (:status @captured)))
        (is (= "create-invite" (:action @log-captured)))
        (is (= {:token "signed-user@example.com"
                :email "user@example.com"
                :status "pending"}
               (:context @log-captured)))))))

(deftest accept-invite-adds-member
  (let [secret "test-secret"
        token  (org-invites/sign-token secret {:org-id "org-1" :email "u@example.com"})
        add-captured (atom nil)
        consume? (atom false)
        log-captured (atom nil)
        app (ig/init-key :etlp-mapper.handler.invites/accept {:db {:spec ::db}
                                                              :token {}})]
    (with-redefs [org-invites/find-invite (fn [_ _ t]
                                            (when (= t token)
                                              {:organization_id "org-1" :email "u@example.com"}))
                  org-invites/consume-invite (fn [_ _ _] (reset! consume? true))
                  org-members/member? (fn [_ _ _] false)
                  org-members/add-member (fn [_ data] (reset! add-captured data))
                  audit-logs/log! (fn [_ data] (reset! log-captured data))
                  ai-usage-logs/log! (fn [& _] nil)]
      (let [resp (handler/sync-default {:ataraxy/result
                                        (app {:body-params {:token token}
                                              :identity {:user {:id "user-1"}}})})]
        (is (= 200 (:status resp)))
        (is (= {:organization_id "org-1" :user_id "user-1" :role "mapper"}
               @add-captured))
        (is @consume?)
        (is (= "accept-invite" (:action @log-captured)))))))
