(ns etlp-mapper.handler.index
  (:require [ataraxy.core :as ataraxy]
            [ataraxy.response :as response]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/index [_ options]
  (fn [{[_] :ataraxy/result}]
    [::response/ok {:message "I'm Clojure"}]))
