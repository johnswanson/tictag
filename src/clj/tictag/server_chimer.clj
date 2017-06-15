(ns tictag.server-chimer
  (:require [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [tictag.db :as db]
            [chime :refer [chime-at]]
            [clj-time.coerce :as tc]
            [taoensso.timbre :as timbre]
            [tictag.slack :as slack]))

(defn chime! [{db :db}]
  (let [state (atom (cycle (shuffle (range 1000))))
        next! (fn [] (swap! state next) (str (first @state)))]
    (fn [time]
      (let [long-time (tc/to-long time)
            id        (next!)]
        (timbre/debugf "------ CHIME! (id %s) ------" id)
        (timbre/debugf "CHIME %s: Adding 'afk' pings" id)
        (db/add-pending! db time id)
        (timbre/debugf "CHIME %s: Sending slack messages" id)
        (slack/send-messages
         (db/get-all-users db)
         (format "`ping %s [%s]`" id long-time))
        (timbre/debugf "CHIME %s: All done!" id)))))

(defrecord ServerChimer [db]
  component/Lifecycle
  (start [component]
    (assoc
     component
     :stop
     (chime-at
      (db/pings db)
      (chime! component))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))

    (dissoc component :stop)))
