(defproject tictag "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :clean-targets ^{:protect false} ["resources/public/js" :target]
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :figwheel {:css-dirs ["resources/public/css"]}
  :cljsbuild {:builds [{:id           "devcards"
                        :figwheel     {:devcards true}
                        :source-paths ["src/cljs" "src/cljc" "cards"]
                        :compiler     {:main                 "cards.core"
                                       :asset-path           "js/devcards_out"
                                       :output-to            "resources/public/js/tictag_devcards.js"
                                       :output-dir           "resources/public/js/devcards_out"
                                       :source-map-timestamp true}}
                       {:id           "dev"
                        :figwheel     true
                        :source-paths ["src/cljs" "src/cljc"]
                        :compiler     {:main          "tictag.dev"
                                       :asset-path    "/js/compiled"
                                       :output-to     "resources/public/js/compiled/app.js"
                                       :output-dir    "resources/public/js/compiled"
                                       :optimizations :none
                                       :source-map    true}}
                       {:id           "prod"
                        :jar          true
                        :figwheel     false
                        :source-paths ["src/cljs" "src/cljc"]
                        :compiler     {:main            "tictag.prod"
                                       :closure-defines {goog.DEBUG false}
                                       :asset-path      "/js/compiled"
                                       :output-to       "resources/public/js/compiled/app.js"
                                       :optimizations   :advanced}}]}
  :plugins [[lein-cljsbuild "1.1.5"]]
  :test-paths ["test/clj"]
  :profiles {:uberjar {:aot        :all
                       :prep-tasks ["compile" ["cljsbuild" "once" "prod"]]}
             :dev     [:dev-secrets {:source-paths ["dev" "test/clj"]
                                     :dependencies [[org.clojure/tools.namespace "0.2.11"]
                                                    [devcards "0.2.4"]
                                                    [com.cemerick/piggieback "0.2.2"]]
                                     :plugins      [[lein-environ "1.1.0"]]}]}
  :source-paths ["src/clj" "src/cljc"]
  :main tictag.main
  :repl-options {:init-ns user :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :target-path "target/%s"
  :uberjar-name "tictag.jar"
  :dependencies [[org.clojure/clojure "1.9.0-alpha16"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/spec.alpha "0.1.143"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/core.async "0.3.465"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [org.clojars.jds02006/tictagapi "0.1.0-SNAPSHOT"]
                 [clj-iterate "0.96"]
                 [instaparse "1.4.8"]
                 [org.clojure/data.codec "0.1.1"]
                 [oauth-clj "0.1.15"]
                 [pandect "0.6.1"]
                 [figwheel-sidecar "0.5.14" :scope "test"]
                 [honeysql "0.8.2"]
                 [nilenso/honeysql-postgres "0.2.2"]
                 [reloaded.repl "0.2.4"]
                 [binaryage/devtools "0.9.7"]
                 [org.clojars.jds02006/cljs-time "0.5.0-SNAPSHOT"]
                 [slingshot "0.12.2"]
                 [ring-middleware-format "0.7.2"]
                 [ring/ring-json "0.4.0"]
                 [cljs-ajax "0.7.3"]
                 [re-frame "0.10.2"]
                 [reagent "0.8.0-alpha2"]
                 [day8.re-frame/async-flow-fx "0.0.8"]
                 [day8.re-frame/http-fx "0.1.4"]
                 [org.postgresql/postgresql "42.1.4"]
                 [ragtime "0.7.2"]
                 [hiccup "1.0.5"]
                 [ring/ring-defaults "0.3.1"]
                 [fogus/ring-edn "0.3.0"]
                 [amalloy/ring-buffer "1.2.1"]
                 [clj-time "0.14.2"]
                 [me.raynes/conch "0.8.0"]
                 [jarohen/chime "0.2.2"]
                 [http-kit "2.2.0"]
                 [cheshire "5.8.0"]
                 [com.stuartsierra/component "0.3.2"]
                 [compojure "1.6.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.taoensso/carmine "2.16.0"]
                 [com.taoensso/sente "1.11.0"]
                 [com.fzakaria/slf4j-timbre "0.3.7"]
                 [environ "1.1.0"]
                 [buddy "2.0.0"]
                 [buddy/buddy-hashers "1.3.0"]
                 [funcool/struct "1.1.0"]
                 [hikari-cp "1.8.2"]
                 [bidi "2.1.2"]
                 [kibu/pushy "0.3.8"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [io.clojure/liberator-transit "0.3.1"]
                 [liberator "0.15.1"]])
