(ns tictag.server-chimer
  (:require [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [tictag.db :as db]
            [chime :refer [chime-at]]
            [clj-time.coerce :as tc]
            [taoensso.timbre :as timbre]
            [tictag.slack :as slack]))

(defrecord ServerChimer [db slack]
  component/Lifecycle
  (start [component]
    (let [state (atom (cycle (shuffle (range 1000))))
          next! (fn [] (swap! state next) (str (first @state)))]
      (assoc
       component
       :stop
       (chime-at
        (db/pings db)
        (fn [time]
          (let [long-time (tc/to-long time)
                id        (next!)]
            (timbre/debug "CHIME!")
            (db/add-pending! db time id)
            (doseq [slack-user (map :slack (db/get-all-users db))]
              (slack/send-message! slack-user (format "PING! id: %s, long-time: %d" id long-time)))))))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))

    (dissoc component :stop)))
