(ns etlp-mapper.handler.mappings-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [etlp-mapper.handler.mappings :as example]))

(deftest smoke-test
  (testing "example page exists"
    (let [handler  (ig/init-key :etlp-mapper.handler/mappings {})
          response (handler (mock/request :get "/mappings"))]
      (is (= :ataraxy.response/ok (first response)) "response ok"))))
