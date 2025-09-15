(ns etlp-mapper.invites-handler-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [etlp-mapper.handler.invites]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members]
            [etlp-mapper.audit-logs :as audit-logs]))

(deftest create-requires-admin
  (let [app (ig/init-key :etlp-mapper.handler.invites/create {:db ::db
                                                              :token {:app-secret "s"}})
        resp (app {:ataraxy/result [nil "org-1"]
                    :body-params {:email "user@example.com"}
                    :identity {:user {:id "u1"}
                               :roles #{:user}}})]
    (is (= 403 (:status resp)))))

(deftest create-stores-invite
  (let [secret "s"
        captured (atom nil)
        log-captured (atom nil)
        app (ig/init-key :etlp-mapper.handler.invites/create {:db ::db
                                                               :token {:app-secret secret}})]
    (with-redefs [org-invites/upsert-invite (fn [_ data] (reset! captured data))
                  audit-logs/log! (fn [_ data] (reset! log-captured data))]
      (let [resp (app {:ataraxy/result [nil "org-1"]
                       :body-params {:email "user@example.com"}
                       :identity {:user {:id "user-1"}
                                  :roles #{:admin}}})]
        (is (= 200 (:status resp)))
        (is (:token (:body resp)))
        (is (= "org-1" (:organization_id @captured)))
        (is (= "user@example.com" (:email @captured)))
        (is (= "create-invite" (:action @log-captured)))
        (is (org-invites/verify-token secret (:token (:body resp)))))))

(deftest accept-invite-adds-member
  (let [secret "s"
        token  (org-invites/sign-token secret {:org-id "org-1" :email "u@example.com"})
        add-captured (atom nil)
        consume? (atom false)
        log-captured (atom nil)
        app (ig/init-key :etlp-mapper.handler.invites/accept {:db ::db
                                                              :token {:app-secret secret}})]
    (with-redefs [org-invites/find-invite (fn [_ t]
                                            (when (= t token)
                                              {:organization_id "org-1" :email "u@example.com"}))
                  org-invites/consume-invite (fn [_ _] (reset! consume? true))
                  org-members/add-member (fn [_ data] (reset! add-captured data))
                  audit-logs/log! (fn [_ data] (reset! log-captured data))]
      (let [resp (app {:body-params {:token token}
                       :identity {:user {:id "user-1"}}})]
        (is (= 200 (:status resp)))
        (is (= {:organization_id "org-1" :user_id "user-1" :role "mapper"}
               @add-captured))
        (is @consume?)
        (is (= "accept-invite" (:action @log-captured)))))))
