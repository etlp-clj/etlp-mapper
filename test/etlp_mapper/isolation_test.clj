(ns etlp-mapper.isolation-test
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members]
            [etlp-mapper.organization-subscriptions :as org-subs]
            [etlp-mapper.handler.invites]))

(deftest invite-dao-queries-are-scoped
  (testing "find-invite filters by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'org-invites/find-invite* ::spec "org-1" "tok-1"))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["tok-1" "org-1"] params)))))
  (testing "consume-invite filters by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/delete! (fn [_ _ where] (reset! captured where) 1)]
        (@#'org-invites/consume-invite* ::spec "org-1" "tok-1"))
      (let [[where & params] @captured]
        (is (re-find #"organization_id" where))
        (is (= ["tok-1" "org-1"] params)))))
  (testing "upsert updates scoped records only"
    (let [captured (atom nil)]
      (with-redefs [jdbc/update! (fn [_ _ set-map where]
                                   (reset! captured [set-map where])
                                   [])]
        (@#'org-invites/update-invite* ::spec {:organization_id "org-1"
                                               :token "tok-1"
                                               :email "e@example.com"}))
      (let [[set-map where] @captured]
        (is (= {:email "e@example.com"} set-map))
        (is (re-find #"organization_id" (first where)))
        (is (= ["tok-1" "org-1"] (rest where)))))))

(deftest membership-dao-queries-are-scoped
  (testing "listing members requires matching organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'org-members/find-members* ::spec "org-1"))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1"] params)))))
  (testing "membership checks constrain by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (is (false? (@#'org-members/member?* ::spec "org-1" "user-1"))))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1" "user-1"] params)))))
  (testing "role checks constrain by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (is (false? (@#'org-members/has-role?* ::spec "org-1" "user-1" "admin"))))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1" "user-1" "admin"] params)))))
  (testing "removing members constrains by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/delete! (fn [_ _ where] (reset! captured where) 1)]
        (@#'org-members/remove-member* ::spec "org-1" "user-1"))
      (let [[where & params] @captured]
        (is (re-find #"organization_id" where))
        (is (= ["org-1" "user-1"] params))))))

(deftest subscription-dao-queries-are-scoped
  (testing "subscription lookups are organization scoped"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'org-subs/find-subscription* ::spec "org-1"))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1"] params)))))
  (testing "subscription updates include organization predicate"
    (let [captured (atom nil)]
      (with-redefs [jdbc/update! (fn [_ _ data where]
                                   (reset! captured [data where])
                                   [])]
        (@#'org-subs/update-subscription* ::spec "org-1" {:plan "pro"}))
      (let [[data where] @captured]
        (is (= {:plan "pro"} data))
        (is (re-find #"organization_id" (first where)))
        (is (= ["org-1"] (rest where)))))))

(deftest log-dao-queries-are-scoped
  (testing "audit log lookup filters by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'audit-logs/find-log* ::spec "org-1" 1))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= [1 "org-1"] params)))))
  (testing "audit log listings filter by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'audit-logs/find-logs* ::spec "org-1"))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1"] params)))))
  (testing "ai usage lookup filters by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'ai-usage-logs/find-usage* ::spec "org-1" 1))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= [1 "org-1"] params)))))
  (testing "ai usage listings filter by organization"
    (let [captured (atom nil)]
      (with-redefs [jdbc/query (fn [_ sql] (reset! captured sql) [])]
        (@#'ai-usage-logs/find-usage-for-org* ::spec "org-1"))
      (let [[sql & params] @captured]
        (is (re-find #"organization_id" sql))
        (is (= ["org-1"] params)))))
  (testing "audit log writes include organization context"
    (let [captured (atom nil)]
      (with-redefs [audit-logs/create-log (fn [_ data] (reset! captured data))]
        (audit-logs/log! ::db {:org-id "org-1"
                               :user-id "user-1"
                               :action "login"
                               :context {:ip "127.0.0.1"}}))
      (is (= "org-1" (:organization_id @captured)))
      (is (= "user-1" (:user_id @captured)))
      (is (re-find #"ip" (:context @captured)))))
  (testing "ai usage writes include organization context"
    (let [captured (atom nil)]
      (with-redefs [ai-usage-logs/log-usage (fn [_ data] (reset! captured data))]
        (ai-usage-logs/log! ::db {:org-id "org-1"
                                  :user-id "user-1"
                                  :feature-type "transform"
                                  :input-tokens 10
                                  :output-tokens 5}))
      (is (= "org-1" (:organization_id @captured)))
      (is (= "user-1" (:user_id @captured)))
      (is (= "transform" (:feature_type @captured))))))

(deftest invite-handlers-require-organization-context
  (let [create-handler (ig/init-key :etlp-mapper.handler.invites/create {:db ::db})
        accept-handler (ig/init-key :etlp-mapper.handler.invites/accept {:db ::db})]
    (testing "create invite rejects missing organization"
      (is (= [::response/forbidden {:error "Organization context required"}]
             (create-handler {:ataraxy/result [::create "org-1"]
                              :identity {:org/id nil
                                         :roles #{:admin}
                                         :user {:id "user-1"}}}))))
    (testing "create invite rejects organization mismatches"
      (is (= [::response/forbidden {:error "Organization mismatch"}]
             (create-handler {:ataraxy/result [::create "org-2"]
                              :identity {:org/id "org-1"
                                         :roles #{:admin}
                                         :user {:id "user-1"}}}))))
    (testing "create invite rejects callers without admin role"
      (is (= [::response/forbidden {:error "Insufficient role"}]
             (create-handler {:ataraxy/result [::create "org-1"]
                              :identity {:org/id "org-1"
                                         :roles #{:member}
                                         :user {:id "user-1"}}}))))
    (testing "create invite logs scoped token issuance"
      (let [logged (atom nil)
            [status body] (with-redefs [audit-logs/log! (fn [_ entry] (reset! logged entry))]
                             (create-handler {:ataraxy/result [::create "org-1"]
                                              :identity {:org/id "org-1"
                                                         :roles #{:admin}
                                                         :user {:id "user-1"}}}))]
        (is (= ::response/ok status))
        (is (= "org-1" (:org_id body)))
        (is (string? (:token body)))
        (is (= {:org-id "org-1"
                :user-id "user-1"
                :action "create-invite"
                :context {:token (:token body)}}
               @logged))))
    (testing "accept invite rejects missing organization context"
      (is (= [::response/forbidden {:error "Organization context required"}]
             (accept-handler {:body-params {:token "tok-1"}
                              :identity {:org/id nil
                                         :user {:id "user-1"}}}))))
    (testing "accept invite rejects mismatched body organization"
      (is (= [::response/forbidden {:error "Organization mismatch"}]
             (accept-handler {:body-params {:token "tok-1" :org_id "org-2"}
                              :identity {:org/id "org-1"
                                         :user {:id "user-1"}}}))))
    (testing "accept invite requires a token"
      (is (= [::response/bad-request {:error "Invalid token"}]
             (accept-handler {:body-params {:org_id "org-1"}
                              :identity {:org/id "org-1"
                                         :user {:id "user-1"}}}))))
    (testing "accept invite logs within the active organization"
      (let [logged (atom nil)
            [status body] (with-redefs [audit-logs/log! (fn [_ entry] (reset! logged entry))]
                             (accept-handler {:body-params {:token "tok-1" :org_id "org-1"}
                                              :identity {:org/id "org-1"
                                                         :user {:id "user-1"}}}))]
        (is (= ::response/ok status))
        (is (= {:org_id "org-1" :token "tok-1" :status "accepted"} body))
        (is (= {:org-id "org-1"
                :user-id "user-1"
                :action "accept-invite"
                :context {:token "tok-1"}}
               @logged))))))
