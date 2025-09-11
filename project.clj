(defproject etlp-mapper "0.1.0-SNAPSHOT"
  :description "ETLP: Mapper Service"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [duct/core "0.8.0"]
                 [duct/handler.sql "0.4.0"]
                 [duct/module.ataraxy "0.3.0"]
                 [duct/module.logging "0.5.0"]
                 [ring-cors "0.1.13"]
                 [ring/ring-core "1.12.2"]
                 [metosin/ring-http-response "0.9.4"]
                 [com.auth0/java-jwt "4.4.0"]
                 [com.auth0/jwks-rsa "0.22.1"]
                 [com.fasterxml.jackson.core/jackson-databind "2.12.7.1"]
                 [duct/module.sql "0.6.1" :exclusions [integrant medley]]
                 [duct/module.web "0.7.3" :exclusions [integrant medley ring/ring-core com.fasterxml.jackson.core/jackson-databind]]
                 [clj-http "3.12.3"]
                 [com.health-samurai/jute "0.2.0-SNAPSHOT"]
                 [org.postgresql/postgresql "42.2.19"]]
  :plugins [[duct/lein-duct "0.12.3"]
            [lein-cloverage "1.2.2"]
            [com.github.clj-kondo/lein-clj-kondo "0.2.5"]]
  :aliases {"clj-kondo-deps" ["with-profile" "+test" "clj-kondo" "--copy-configs" "--dependencies" "--parallel" "--lint" "$classpath"]
            "clj-kondo-lint" ["do" ["clj-kondo-deps"] ["with-profile" "+test" "clj-kondo"]]}
  :main ^:skip-aot etlp-mapper.main
  :resource-paths ["resources" "target/resources"]
  :prep-tasks     ["javac" "compile"]
  :middleware     [lein-duct.plugin/middleware]
  :profiles
  {:dev           [:project/dev :profiles/dev]
   :prod          [:project/prod :profiles/prod]
   :repl          {:prep-tasks   ^:replace ["javac" "compile"]
                   :repl-options {:init-ns user}}
   :uberjar       {:aot :all}
   :profiles/prod {}
   :project/prod  {:source-paths   ["prod/src"]
                   :resource-paths ["prod/resources"]}
   :profiles/dev  {} 
   :project/dev   {:source-paths   ["dev/src"]
                   :resource-paths ["dev/resources"]
                   :dependencies   [[integrant/repl "0.3.2" :exclusions [integrant]]
                                    [hawk "0.2.11"]
                                    [eftest "0.5.9"]
                                    [kerodon "0.9.1"]]}})
