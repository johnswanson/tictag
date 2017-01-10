(defproject tictag "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:dev [:secrets {:source-paths ["dev"]
                             :dependencies [[org.clojure/tools.namespace "0.2.11"]
                                            [reloaded.repl "0.2.3"]]
                             :plugins [[lein-environ "1.1.0"]]}]}
  :main tictag.main
  :repl-options {:init-ns user}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/core.async "0.2.395"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/java.jdbc "0.7.0-alpha1"]
                 [org.xerial/sqlite-jdbc "3.15.1"]
                 [ring/ring-defaults "0.2.1"]
                 [fogus/ring-edn "0.3.0"]
                 [amalloy/ring-buffer "1.2.1"]
                 [clj-time "0.12.2"]
                 [me.raynes/conch "0.8.0"]
                 [jarohen/chime "0.2.0"]
                 [http-kit "2.2.0"]
                 [cheshire "5.6.3"]
                 [com.stuartsierra/component "0.3.2"]
                 [compojure "1.5.1"]
                 [com.taoensso/timbre "4.8.0"]
                 [environ "1.1.0"]
                 [clojure-csv/clojure-csv "2.0.1"]])
