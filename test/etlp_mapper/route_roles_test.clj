(ns etlp-mapper.route-roles-test
  (:require [clojure.test :refer :all]
            [ring.util.http-response :as http]
            [etlp-mapper.auth :as auth]))

(def ok-handler (fn [_] (http/ok)))

(deftest orgs-create-owner-only
  (let [app ((auth/require-role :owner) ok-handler)
        allowed (app {:identity {:roles #{:owner}}})
        denied (app {:identity {:roles #{:admin}}})]
    (is (= 200 (:status allowed)))
    (is (= 403 (:status denied)))))

(deftest set-active-org-admin-only
  (let [app ((auth/require-role :admin) ok-handler)
        allowed (app {:identity {:roles #{:admin}}})
        denied (app {:identity {:roles #{:editor}}})]
    (is (= 200 (:status allowed)))
    (is (= 403 (:status denied)))))

(deftest invites-create-admin-or-owner
  (let [app ((auth/require-any-role [:admin :owner]) ok-handler)
        admin-resp (app {:identity {:roles #{:admin}}})
        owner-resp (app {:identity {:roles #{:owner}}})
        denied (app {:identity {:roles #{:editor}}})]
    (is (= 200 (:status admin-resp)))
    (is (= 200 (:status owner-resp)))
    (is (= 403 (:status denied)))))

(deftest billing-portal-admin-or-owner
  (let [app ((auth/require-any-role [:admin :owner]) ok-handler)
        admin-resp (app {:identity {:roles #{:admin}}})
        owner-resp (app {:identity {:roles #{:owner}}})
        denied (app {:identity {:roles #{:editor}}})]
    (is (= 200 (:status admin-resp)))
    (is (= 200 (:status owner-resp)))
    (is (= 403 (:status denied)))))
