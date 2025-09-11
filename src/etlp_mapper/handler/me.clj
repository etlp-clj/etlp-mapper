(ns etlp-mapper.handler.me
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

;; POST /me/active-org – set the currently active organisation for the user.
;; The real implementation would persist this choice and perhaps issue a new
;; token.  Here we simply echo back the requested organisation identifier.
(defmethod ig/init-key :etlp-mapper.handler.me/set-active-org
  [_ _]
  (fn [{{:keys [org_id]} :body-params}]
    [::response/ok {:org_id org_id}]))

