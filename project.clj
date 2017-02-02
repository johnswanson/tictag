(defproject tictag "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :figwheel {:css-dirs ["resources/public/css"]}
  :cljsbuild {:builds [{:id           "dev"
                        :figwheel     true
                        :source-paths ["src/cljs"]
                        :compiler     {:main          "tictag.dev"
                                       :asset-path    "/js/compiled"
                                       :output-to     "resources/public/js/compiled/app.js"
                                       :output-dir    "resources/public/js/compiled"
                                       :optimizations :none
                                       :source-map    true}}]}
  :plugins [[lein-cljsbuild "1.1.5"]]
  :test-paths ["test/clj"]
  :profiles {:uberjar {:hooks [leiningen.cljsbuild]
                       :aot :all}
             :dev [:dev-secrets {:source-paths ["dev" "test/clj"]
                                 :dependencies [[org.clojure/tools.namespace "0.2.11"]
                                                [com.cemerick/piggieback "0.2.1"]]
                                 :plugins [[lein-environ "1.1.0"]]}]}
  :source-paths ["src/clj"]
  :main tictag.main
  :repl-options {:init-ns user :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :target-path "target/%s"
  :uberjar-name "standalone.jar"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojure/core.async "0.2.395"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/java.jdbc "0.7.0-alpha1"]
                 [org.clojars.jds02006/tictagapi "0.1.0-SNAPSHOT"]
                 [org.clojure/data.codec "0.1.0"]
                 [pandect "0.6.1"]
                 [figwheel-sidecar "0.5.8" :scope "test"]
                 [reloaded.repl "0.2.3"]
                 [binaryage/devtools "0.8.2"]
                 [com.andrewmcveigh/cljs-time "0.5.0-alpha2"]
                 [day8.re-frame/http-fx "0.1.3"]
                 [ring-transit "0.1.6"]
                 [cljs-ajax "0.5.8"]
                 [day8.re-frame/async-flow-fx "0.0.6"]
                 [org.postgresql/postgresql "9.4.1212"]
                 [org.xerial/sqlite-jdbc "3.15.1"]
                 [ragtime "0.6.3"]
                 [re-frame "0.9.1"]
                 [hiccup "1.0.5"]
                 [ring/ring-defaults "0.2.1"]
                 [fogus/ring-edn "0.3.0"]
                 [amalloy/ring-buffer "1.2.1"]
                 [clj-time "0.12.2"]
                 [me.raynes/conch "0.8.0"]
                 [jarohen/chime "0.2.0"]
                 [http-kit "2.2.0"]
                 [cheshire "5.6.3"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.danielsz/system "0.3.1"]
                 [im.chit/hara.io.watch "2.4.8"]
                 [compojure "1.5.1"]
                 [com.taoensso/timbre "4.8.0"]
                 [environ "1.1.0"]
                 [clojure-csv/clojure-csv "2.0.1"]])
