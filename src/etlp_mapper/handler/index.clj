(ns etlp-mapper.handler.index
  (:require [ataraxy.core :as ataraxy]
            [ataraxy.response :as response]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/index [_ options]
  (fn [{[_] :ataraxy/result}]
    [::response/ok {:message "I'm Clojure"}]))


(defn hello-middleware
  "Middleware that adds a custom header to responses."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (assoc-in response [:headers "X-Hello"] "Hello, Duct!"))))


(defn cors-middleware
  "Middleware that allows CORS requests and handles preflight OPTIONS."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status  200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"}
       :body    "OK"}
      (let [response (handler request)]
        (-> response
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
            (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
            (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization")
            (assoc-in [:headers "Access-Control-Expose-Headers"] "Location"))))))


(defmethod ig/init-key :etlp-mapper.handler/hello [_ _]
  cors-middleware)
