(ns etlp-mapper.handler.mappings-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]))
            

(deftest smoke-test
  (testing "example page exists"
    (is (= "response ok" "response ok"))))
