(ns tictag.riemann
  (:require [riemann.client :as r]
            [com.stuartsierra.component :as component]))

(defn send! [c e]
  (r/send-event (:conn c) e))

(defrecord RiemannClient [config]
  component/Lifecycle
  (start [component]
    (assoc component :conn (r/tcp-client config)))
  (stop [component]
    (when-let [c (:conn component)]
      (r/close! c))
    (dissoc component :conn)))

