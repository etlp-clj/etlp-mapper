(ns etlp-mapper.handler.mappings-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [etlp-mapper.handler.mappings :as example]))

(deftest smoke-test
  (testing "example page exists"
    (is (= "response ok" "response ok"))))


(defn string->stream
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))


(def test_result (example/create {:body "test/etlp_mapper/resources/csv.yml"}))

(clojure.pprint/pprint test_result)
