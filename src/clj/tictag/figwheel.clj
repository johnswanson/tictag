(ns tictag.figwheel
  (:require [com.stuartsierra.component :as component]
            [figwheel-sidecar.repl-api :as ra]))

(defrecord Figwheel [run-figwheel?]
  component/Lifecycle
  (start [component]
    (if run-figwheel?
      (assoc component :figwheel (ra/start-figwheel!))
      component))
  (stop [component]
    (dissoc component :figwheel)))
