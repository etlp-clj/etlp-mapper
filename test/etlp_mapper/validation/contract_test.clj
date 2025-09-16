(ns etlp-mapper.validation.contract-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [ring.util.http-response :as http]
            [etlp-mapper.auth :as auth]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs])
  (:import (java.security KeyPairGenerator)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)))

(def issuer "http://issuer")
(def audience "audience")

(defn token-with-claims [claims]
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        kp  (.generateKeyPair kpg)
        pub (.getPublic kp)
        priv (.getPrivate kp)
        alg (Algorithm/RSA256 pub priv)
        builder (-> (JWT/create)
                    (.withIssuer issuer)
                    (.withAudience (into-array String [audience])))
        builder (reduce (fn [b [k v]] (.withClaim b (name k) (str v))) builder claims)
        token (.sign builder alg)
        verifier (fn [t]
                   (-> (JWT/require alg)
                       (.withIssuer issuer)
                       (.withAudience (into-array String [audience]))
                       .build
                       (.verify t)))]
    {:token token :verifier verifier}))

(deftest unauthorized-when-missing-authorization
  (let [handler (fn [_] (http/ok))
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier (constantly nil) :db ::db})
             ((auth/wrap-require-org) handler))
        resp (app (mock/request :get "/mappings"))]
    (is (= 401 (:status resp)))))

(deftest authorized-request-with-header-org
  (let [{:keys [token verifier]} (token-with-claims {:sub "user-1" :email "user@example.com"})
        handler (fn [req] (http/ok (get-in req [:identity :roles])))
        upsert (fn [_ _] {:id "user-1" :email "user@example.com" :idp_sub "sub" :last_used_org_id nil})
        roles  (fn [& _] ["editor"])
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier verifier :db ::db})
             ((auth/wrap-require-org) handler))]
    (with-redefs [auth/upsert-user! upsert
                  auth/load-user-roles roles
                  auth/update-last-org! (fn [& _] nil)
                  audit-logs/log! (fn [& _] nil)
                  ai-usage-logs/log! (fn [& _] nil)]
      (let [resp (app (-> (mock/request :get "/mappings")
                          (mock/header "authorization" (str "Bearer " token))
                          (mock/header "x-org-id" "org-1")))]
        (is (= 200 (:status resp)))
        (is (= #{:editor} (get-in resp [:body])))))))

(deftest forbidden-when-no-org-context
  (let [{:keys [token verifier]} (token-with-claims {:sub "user-1" :email "user@example.com"})
        handler (fn [_] (http/ok))
        upsert (fn [_ _] {:id "user-1" :email "user@example.com" :idp_sub "sub" :last_used_org_id nil})
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier verifier :db ::db})
             ((auth/wrap-require-org) handler))]
    (with-redefs [auth/upsert-user! upsert
                  auth/load-user-roles (fn [& _] [])
                  auth/update-last-org! (fn [& _] nil)]
      (let [resp (app (mock/header (mock/request :get "/mappings") "authorization" (str "Bearer " token)))]
        (is (= 403 (:status resp)))))))

(deftest role-guard-contract
  (let [handler (fn [_] (http/ok))
        app ((auth/require-any-role [:admin :owner]) handler)]
    (is (= 200 (:status (app {:identity {:roles #{:admin}}}))))
    (is (= 403 (:status (app {:identity {:roles #{:editor}}}))))))
