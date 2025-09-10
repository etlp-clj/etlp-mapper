(ns etlp-mapper.handler.invites
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

(defn- admin-role? [roles]
  (some #{:owner "owner" :admin "admin"} roles))

;; POST /orgs/:org-id/invites – create an invite token.  Requires the caller to
;; have an admin or owner role within the organisation.  Token generation and
;; persistence are stubbed out.
(defmethod ig/init-key :etlp-mapper.handler.invites/create
  [_ _]
  (fn [{[_ org-id] :ataraxy/result :as request}]
    (let [roles (get-in request [:identity :claims :roles])]
      (if (admin-role? roles)
        (let [token (str (java.util.UUID/randomUUID))]
          [::response/ok {:org_id org-id :token token}])
        [::response/forbidden {:error "Insufficient role"}]))))

;; POST /invites/accept – accept an invite token.  In a full system the token
;; would be validated and the user added to the organisation along with an
;; audit entry.  Here we simply echo back the token and supplied organisation
;; identifier.
(defmethod ig/init-key :etlp-mapper.handler.invites/accept
  [_ _]
  (fn [{{:keys [token org_id]} :body-params}]
    (if token
      [::response/ok {:org_id org_id :token token :status "accepted"}]
      [::response/bad-request {:error "Invalid token"}])))

