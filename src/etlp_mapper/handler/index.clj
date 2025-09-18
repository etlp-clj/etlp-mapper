(ns etlp-mapper.handler.index
  (:require [ataraxy.response :as response]
            [ataraxy.core :as ataraxy]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/index [_ _]
  (fn [{[_] :ataraxy/result}]
    [::response/ok {:message "I'm Clojure"}]))
