(ns etlp-mapper.isolation-test
  (:require [clojure.test :refer :all]))

(defonce mappings (atom {}))

(use-fixtures :each (fn [f]
                      (reset! mappings {1 {:org-id "org-1" :value "a"}
                                         2 {:org-id "org-2" :value "b"}})
                      (f)))

(defn fetch [org-id id]
  (let [row (get @mappings id)]
    (when (= org-id (:org-id row)) row)))

(deftest disallow-cross-org-access
  (is (nil? (fetch "org-1" 2)))
  (is (= {:org-id "org-1" :value "a"} (fetch "org-1" 1))))

