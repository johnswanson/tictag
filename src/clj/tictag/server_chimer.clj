(ns tictag.server-chimer
  (:require [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [tictag.db :as db]
            [chime :refer [chime-at]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [taoensso.timbre :as timbre]
            [tictag.slack :as slack]
            [clj-time.format :as f]
            [tictag.utils :as utils]))

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
        (let [slacks    (db/get-all-slacks db)
              messages (->> slacks
                            (map :users.tz)
                            (map t/time-zone-for-id)
                            (map #(f/with-zone utils/wtf %))
                            (map #(format "%s\n`ping %s [%s]`" (f/unparse % time) id long-time)))
              responses (doall
                         (slack/send-chime! slacks messages))]
          (db/update-tags-with-slack-ts db time responses))
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
