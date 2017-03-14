(ns tictag.re-frame
  (:require [re-frame.core :as re-frame]
            [com.stuartsierra.component :as component]
            [tictag.events]))

(defrecord Reframe [db beeminder tagtime twilio calendar slack shared-secret]
  component/Lifecycle
  (start [component]
    (tictag.events/register! component))
  (stop [component]
    component))

