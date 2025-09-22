(ns etlp-mapper.db
  "Runtime helpers for working with the shared database datasource.")

(defonce ^:private datasource* (atom nil))

(defn set-datasource!
  "Record the given datasource for later retrieval.

  This is primarily useful for imperative helpers and tests that need
  access to the application's configured datasource."
  [ds]
  (reset! datasource* ds)
  ds)

(defn get-datasource
  "Return the configured datasource or throw if none has been registered."
  []
  (or @datasource*
      (throw (ex-info "Datasource has not been configured" {}))))
