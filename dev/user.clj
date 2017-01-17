(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [taoensso.timbre :as timbre]
            [reloaded.repl :refer [system init start stop go reset]]
            [com.stuartsierra.component :as component]
            [me.raynes.conch :refer [with-programs]]
            [system.components
             [watcher :refer [new-watcher]]]
            [figwheel-sidecar.repl :as r]
            [figwheel-sidecar.system :as fs]
            [figwheel-sidecar.repl-api :as ra]
            [tictag.config :as config]
            [tictag.server :as server]
            [tictag.server-api :as api]
            [tictag.client :as client]
            [tictag.client-config :as client-config]
            [tictag.utils :as utils]))

(defrecord Figwheel [config]
  component/Lifecycle
  (start [component]
    (assoc component :figwheel (ra/start-figwheel! config)))
  (stop [component]
    (dissoc component :figwheel)))

(defn figwheel [config]
  (->Figwheel config))

(defn scss-compiler
  []
  (new-watcher ["./resources/scss"]
               (fn [action f]
                 (timbre/infof "%s %s, rebuilding app.css" action f)
                 (with-programs [sassc]
                   (sassc
                    "-m"
                    "-I" "resources/scss/"
                    "-t" "nested"
                    "resources/scss/app.scss"
                    "resources/public/css/app.css"))
                 (timbre/info "app.css build complete"))))

;; We keep this separate from the overall system in order to start it with CIDER.
(def figwheel-component
  (figwheel {:figwheel-options {:css-dirs  ["resources/public/css"]}
             :build-ids        ["dev"]
             :all-builds [{:id           "dev"
                           :figwheel     {:websocket-host "localhost"}
                           :source-paths ["src/cljs"]
                           :compiler     {:main          'tictag.dev
                                          :asset-path    "/js/compiled"
                                          :output-to     "resources/public/js/compiled/app.js"
                                          :output-dir    "resources/public/js/compiled"
                                          :optimizations :none
                                          :source-map    true}}]}))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn server-system []
  (utils/system-map (assoc server/system :scss-compiler (scss-compiler) :figwheel figwheel-component)))

(defn client-system []
  (utils/system-map (client/system (client-config/remote-url))))

(defn sleepy-pings [& args] (apply (partial api/sleepy-pings (:db system)) args))
(defn sleep [& args] (apply (partial api/sleep (:db system)) args))
(defn add-ping! [& args] (apply (partial api/add-ping! (:db system)) args))
(defn update-ping! [& args] (apply (partial api/update-ping! (:db system)) args))
(defn beeminder-sync! [& args] (apply (partial api/beeminder-sync-from-db! config/beeminder (:db system)) args))
(defn pings [& args] (apply (partial api/pings (:db system)) args))
(defn last-ping [& args] (apply (partial api/last-ping (:db system)) args))

(reloaded.repl/set-init! server-system)

;; first:
;; (reloaded.repl/set-init! server-system)
;; or
;; (reloaded.repl/set-init! client-system)

;; then use (reset) (stop) (start), etc.
