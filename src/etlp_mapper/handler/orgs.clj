(ns etlp-mapper.handler.orgs
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

;; Handler for creating a new organization. Requires an authenticated user
;; without an active organisation association. The real implementation would
;; create the org, membership, subscription and audit log entries. Here we
;; simply generate a UUID for the new organisation and return it.

(defmethod ig/init-key :etlp-mapper.handler.orgs/create
  [_ _]
  (fn [request]
    (if (get-in request [:identity :org/id])
      [::response/forbidden {:error "Organization already selected"}]
      (let [new-id (str (java.util.UUID/randomUUID))]
        [::response/ok {:org_id new-id}]))))

