(ns etlp-mapper.handler.billing
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

;; POST /billing/portal – return a stubbed billing portal URL.
(defmethod ig/init-key :etlp-mapper.handler.billing/portal
  [_ _]
  (fn [_]
    [::response/ok {:url "https://billing.example.com/portal"}]))
