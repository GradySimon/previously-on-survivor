(defproject previously-on-survivor "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-ring "0.9.5"]]
  :ring {:handler previously-on-survivor.core/handler}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-time "0.9.0"]
                 [com.datomic/datomic-pro "0.9.5173" :exclusions [joda-time]]
                 [liberator "0.13"]
                 [compojure "1.3.4"]
                 [ring/ring-core "1.4.0-RC1"]]
  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}}
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
