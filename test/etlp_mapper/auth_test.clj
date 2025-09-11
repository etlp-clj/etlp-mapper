(ns etlp-mapper.auth-test
  (:require [clojure.test :refer :all]
            [etlp-mapper.auth :as auth]
            [ring.util.http-response :as http])
  (:import (java.security KeyPairGenerator)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)))

(def issuer "http://issuer")
(def audience "audience")

(defn gen-token
  "Generate a signed JWT and a verifier function.
  `claims` is a map of extra claims to embed."
  [claims]
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

(deftest jwt-success
  (let [{:keys [token verifier]} (gen-token {:org_id "org-1"})
        handler (fn [req] (http/ok (get req :identity)))
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier verifier})
             ((auth/wrap-require-org) handler))
        resp (app {:headers {"authorization" (str "Bearer " token)}})]
    (is (= 200 (:status resp)))
    (is (= "org-1" (get-in resp [:body :org/id])))))

(deftest jwt-missing-org
  (let [{:keys [token verifier]} (gen-token {})
        called (atom false)
        handler (fn [_]
                  (reset! called true)
                  (http/ok))
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier verifier})
             ((auth/wrap-require-org) handler))
        resp (app {:headers {"authorization" (str "Bearer " token)}})]
    (is (= 403 (:status resp)))
    (is (false? @called))))

(deftest jwt-invalid
  (let [{:keys [token]} (gen-token {:org_id "org-1"})
        ;; verifier with wrong audience to force failure
        bad-verifier (fn [t]
                       (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
                             kp  (.generateKeyPair kpg)
                             alg (Algorithm/RSA256 (.getPublic kp) (.getPrivate kp))]
                         (-> (JWT/require alg)
                             (.withIssuer issuer)
                             (.withAudience (into-array String ["other"]))
                             .build
                             (.verify t))))
        handler (fn [_] (http/ok))
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier bad-verifier})
             ((auth/wrap-require-org) handler))
        resp (app {:headers {"authorization" (str "Bearer " token)}})]
    (is (= 401 (:status resp)))
    (is (= "Bearer realm=\"mapify\"" (get-in resp [:headers "WWW-Authenticate"])))) )

(deftest route-protection
  (let [handler (fn [_] (http/ok))
        app ((auth/wrap-auth {:issuer issuer :audience audience :verifier (constantly nil)})
             ((auth/wrap-require-org) handler))
        resp (app {})]
    (is (= 401 (:status resp)))))

(deftest jwks-uri-required
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"JWKS URI must be configured"
                        (auth/wrap-auth {:issuer issuer :audience audience}))))


(deftest role-guard-success
  (let [handler (fn [_] (http/ok))
        app ((auth/require-role :admin) handler)
        resp (app {:identity {:claims {:roles [:admin :user]}}})]
    (is (= 200 (:status resp)))))

(deftest role-guard-failure
  (let [handler (fn [_] (http/ok))
        app ((auth/require-role :admin) handler)
        resp (app {:identity {:claims {:roles [:user]}}})]
    (is (= 403 (:status resp)))))

