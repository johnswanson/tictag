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
            [tictag.server-api :refer :all]
            [tictag.utils :as utils]
            [clojure.test :refer :all]
            [tictag.server-test :as server-test]))

(defrecord Tester []
  component/Lifecycle
  (start [component]
    (assoc component :tests (run-all-tests #"tictag.+")))
  (stop [component]
    (dissoc component :tests)))

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

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj" "test/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def test-component (->Tester))

(defn server-system [config]
  (utils/system-map
   (assoc (server/system config)
          :scss-compiler (scss-compiler)
          :testing test-component)))


(reloaded.repl/set-init! #(server-system config/config))

