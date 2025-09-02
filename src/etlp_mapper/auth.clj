(ns etlp-mapper.auth
  "Ring middleware for validating Keycloak OIDC access tokens."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.http-response :as http])
  (:import (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwk JwkProviderBuilder)
           (java.net URL)
           (java.nio.charset StandardCharsets)
           (java.util Base64)
           (java.util.concurrent TimeUnit)))

(defn- bearer-token
  "Extract the bearer token from the Authorization header."
  [req]
  (some-> (get-in req [:headers "authorization"])
          (str/split #" ")
          second))

(defn- decode-claims
  "Decode the claims map from a verified JWT."
  [decoded]
  (let [payload (.getPayload decoded)
        bytes   (.decode (Base64/getUrlDecoder) payload)]
    (json/parse-string (String. bytes StandardCharsets/UTF_8) true)))

(defn- unauthorized
  [message]
  (-> (http/unauthorized {:error message})
      (http/header "WWW-Authenticate" "Bearer realm=\"etlp\"")))

(defn- forbidden
  [message]
  (http/forbidden {:error message}))

(defn- build-verifier
  "Create a function that verifies tokens using a JWKS URI."
  [issuer audience jwks-uri]
  (let [provider (-> (JwkProviderBuilder. (URL. jwks-uri))
                     (.cached 10 24 TimeUnit/HOURS)
                     .build)]
    (fn [token]
      (let [jwt   (JWT/decode token)
            kid   (.getKeyId jwt)
            jwk   (.get provider kid)
            pub   (.getPublicKey jwk)
            algo  (Algorithm/RSA256 pub nil)
            verifier (-> (JWT/require algo)
                         (.withIssuer issuer)
                         (.withAudience (into-array String [audience]))
                         .build)]
        (.verify verifier token)))))

(defn wrap-auth
  "Middleware factory that validates bearer tokens and attaches an
  `:identity` map to the request on success.

  Options: `:issuer`, `:audience`, `:jwks-uri`.  For testing a custom
  `:verifier` function may be supplied which should return a
  `DecodedJWT` when given a token."
  [{:keys [issuer audience jwks-uri verifier]}]
  (let [verify (or verifier (build-verifier issuer audience jwks-uri))]
    (fn [handler]
      (fn [req]
        (if-let [token (bearer-token req)]
          (try
            (let [decoded (verify token)
                  claims  (decode-claims decoded)
                  org-id  (:org_id claims)
                  identity {:method :oidc
                            :org/id org-id
                            :claims claims}
                  resp    (handler (assoc req :identity identity))]
              (assoc resp :identity identity))
            (catch Exception _
              (unauthorized "Invalid token")))
          (unauthorized "Invalid token"))))))

(defn wrap-require-org
  "Middleware factory that ensures an org/id is present in the request
  identity and returns 403 otherwise."
  ([]
   (fn [handler]
     (fn [req]
       (let [resp (handler req)]
         (cond
           (get-in resp [:identity :org/id]) resp
           (= 200 (:status resp)) (forbidden "Organization context required")
           :else resp))))))

(defn require-role
  "Middleware factory enforcing that the authenticated identity has the
  given role inside a `roles` claim."
  [role]
  (fn [handler]
    (fn [req]
      (let [roles (get-in req [:identity :claims :roles])
            role-str (name role)]
        (if (some #{role role-str} roles)
          (handler req)
          (forbidden "Insufficient role"))))))

;; Public API exports
;; wrap-auth, wrap-require-org and require-role

