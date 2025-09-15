(ns etlp-mapper.identity-test
  (:require [clojure.test :refer :all]
            [etlp-mapper.identity :as identity]))

(deftest org-id-extraction
  (is (= "org-1" (identity/org-id {:identity {:org/id "org-1"}})))
  (is (= "org-2" (identity/org-id {:identity {:org-id "org-2"}})))
  (is (= "org-3" (identity/org-id {:identity {:org_id "org-3"}})))
  (is (nil? (identity/org-id {}))))

(deftest roles-normalization
  (is (= #{:admin :owner}
         (identity/roles {:identity {:roles #{:admin "owner"}}})))
  (is (= #{:admin}
         (identity/roles {:identity {:roles ["admin"]}})))
  (is (= #{:viewer}
         (identity/roles {:identity {:claims {:roles "viewer"}}})))
  (is (empty? (identity/roles {}))))

(deftest user-data-merging
  (let [request {:identity {:user {:id "user-1"
                                   :email "user@example.com"
                                   :idp_sub "sub-123"
                                   :last_used_org_id "org-1"}
                            :claims {:email "token@example.com"
                                     :sub "sub-123"
                                     :exp 123}}}
        user (identity/user request)]
    (is (= "user-1" (:id user)))
    (is (= "user@example.com" (:email user)))
    (is (= "sub-123" (:idp-sub user)))
    (is (= "org-1" (:last-used-org-id user)))
    (is (= 123 (:exp user))))
  (let [request {:identity {:claims {:sub "sub-456" :email "token@example.com"}}}
        user (identity/user request)]
    (is (= {:idp-sub "sub-456" :email "token@example.com"} user))))

(deftest user-id-precedence
  (is (= "user-1" (identity/user-id {:identity {:user {:id "user-1"}}})))
  (is (= "legacy" (identity/user-id {:identity {:claims {:sub "legacy"}}})))
  (is (nil? (identity/user-id {}))))
