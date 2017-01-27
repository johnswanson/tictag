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
            [clojure.tools.nrepl.server :as repl]
            [tictag.config :as config]
            [tictag.server :as server]
            [tictag.server-api :refer :all]
            [tictag.client :as client]
            [tictag.client-config :as client-config]
            [tictag.utils :as utils]))

(defrecord Figwheel []
  component/Lifecycle
  (start [component]
    (assoc component :figwheel (ra/start-figwheel!)))
  (stop [component]
    (dissoc component :figwheel)))

(defn figwheel []
  (->Figwheel))

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
  (figwheel))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn server-system [config]
  (utils/system-map
   (assoc (server/system config)
          :scss-compiler (scss-compiler)
          :figwheel figwheel-component)))

(defn client-system []
  (utils/system-map (client/system client-config/config)))

(defn start-client! []
  (reloaded.repl/set-init! client-system)
  (go))

(defn start-server! []
  (reloaded.repl/set-init! #(server-system config/config))
  (go))

