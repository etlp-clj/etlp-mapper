(ns etlp-mapper.mapping-operations-test
  (:require [clojure.test :refer :all]
            [ring.util.http-response :as http]
            [etlp-mapper.auth :as auth]))

(defn create-handler [req]
  (http/ok {:org/id (get-in req [:identity :org/id])}))


(deftest mapping-create-requires-role
  (let [app ((auth/require-role :editor) create-handler)
        resp (app {:identity {:org/id "org-1" :roles #{:editor}}})]
    (is (= 200 (:status resp)))
    (is (= "org-1" (get-in resp [:body :org/id])))))

(deftest mapping-create-role-forbidden
  (let [app ((auth/require-role :editor) create-handler)
        resp (app {:identity {:org/id "org-1" :roles #{:admin}}})]
    (is (= 403 (:status resp)))))

