(ns etlp-mapper.main
  (:gen-class)
  (:require [duct.core :as duct]
            [etlp-mapper.pgtypes]))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys     (or (duct/parse-keys args) [:duct/daemon])
        profiles [:duct.profile/prod :duct.profile/dev]]
    (-> (duct/resource "etlp_mapper/config.edn")
        (duct/read-config)
        (duct/exec-config profiles keys))
    (System/exit 0)))
