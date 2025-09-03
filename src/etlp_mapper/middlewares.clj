(ns etlp-mapper.middlewares
  (:require
    [ring.middleware.cors :refer [wrap-cors]]
    [integrant.core :as ig]))

(defn cors-middleware
  "Allow cross-origin requests for common HTTP methods and headers."
  [handler]
  (wrap-cors handler
             :access-control-allow-origin [#".*"]
             :access-control-allow-methods [:get :post :put :delete :options]
             :access-control-allow-headers ["Content-Type" "Authorization"]))

(defmethod ig/init-key :etlp-mapper.middleware/cors
  [_ _]
  cors-middleware)
