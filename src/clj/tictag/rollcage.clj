(ns tictag.rollcage
  (:require [circleci.rollcage.core :as rollcage]
            [com.stuartsierra.component :as component]))

(defrecord RollcageClient [token environment]
  component/Lifecycle
  (start [component]
    (let [client (rollcage/client token {:environment environment})]
      (rollcage/setup-uncaught-exception-handler client)
      (assoc component :client client)))
  (stop [component]
    (dissoc component :client)))

