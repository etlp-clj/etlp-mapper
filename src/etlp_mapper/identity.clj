(ns etlp-mapper.identity
  "Helpers for working with the request identity map.")

(defn- ->keyword
  "Convert a role value into a keyword when possible."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        v))

(defn org-id
  "Return the active organization identifier from the request."
  [request]
  (or (get-in request [:identity :org/id])
      (get-in request [:identity :org-id])
      (get-in request [:identity :org_id])))

(defn roles
  "Return the set of roles associated with the authenticated identity.

  Handles both the newer identity shape where roles are provided as a set of
  keywords and the legacy structure where roles live under `:claims` as strings."
  [request]
  (let [raw-roles (or (get-in request [:identity :roles])
                      (get-in request [:identity :claims :roles]))]
    (cond
      (nil? raw-roles) #{}
      (set? raw-roles) (set (map ->keyword raw-roles))
      (sequential? raw-roles) (set (map ->keyword raw-roles))
      :else (set (keep (fn [v]
                         (when v
                           (->keyword v)))
                       (if (coll? raw-roles)
                         raw-roles
                         [raw-roles]))))))

(defn user
  "Return a sanitized representation of the authenticated user.

  The Keycloak integration stores database user information under `:user`, while
  the legacy implementation exposed raw token claims.  This helper merges the
  useful bits of both shapes into a single map with consistent keys."
  [request]
  (let [identity (:identity request)
        user-map (when (map? identity) (:user identity))
        claims   (when (map? identity) (:claims identity))
        last-org (or (:last-used-org-id user-map)
                     (:last_used_org_id user-map)
                     (:last_used_org_id claims))
        email    (or (:email user-map) (:email claims))
        idp-sub  (or (:idp-sub user-map)
                     (:idp_sub user-map)
                     (:sub claims))]
    (cond-> {}
      (:id user-map) (assoc :id (:id user-map))
      email (assoc :email email)
      idp-sub (assoc :idp-sub idp-sub)
      (:exp claims) (assoc :exp (:exp claims))
      last-org (assoc :last-used-org-id last-org))))

(defn user-id
  "Extract the stable user identifier from the request identity.

  Prefers the database identifier exposed by the Keycloak integration but falls
  back to the identity provider subject when running against the legacy stack."
  [request]
  (or (get-in request [:identity :user :id])
      (get-in request [:identity :user/id])
      (get-in request [:identity :user-id])
      (get-in request [:identity :user_id])
      (get-in request [:identity :claims :user-id])
      (get-in request [:identity :claims :user_id])
      (get-in request [:identity :claims :sub]))))
