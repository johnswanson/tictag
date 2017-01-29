(ns tictag.repl
  (:require [clojure.tools.nrepl.server :as repl]
            [com.stuartsierra.component :as component]))

(defrecord REPL []
  component/Lifecycle
  (start [component]
    (assoc component :server (repl/start-server :port 7888)))
  (stop [component]
    (when-let [server (:server component)]
      (repl/stop-server server))
    (dissoc component :server)))
