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
            [tictag.utils :as utils]
            [com.climate.claypoole :as cp]))

(defn chime! [{db :db slack :slack {pool :pool} :threadpool}]
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
                            (map :tz)
                            (map t/time-zone-for-id)
                            (map #(f/with-zone utils/wtf %))
                            (map #(format "%s\n`ping %s [%s]`" (f/unparse % time) id long-time)))]
          (doall
           (for [[params message] (zipmap slacks messages)]
             (cp/future pool
               (when-let [{:keys [body]} (slack/send-message!
                                          slack
                                          (-> params
                                              (assoc :text message)
                                              (dissoc :slack-id)))]
                 (db/update-ping-threads
                  db
                  (:slack-id params)
                  time
                  (:ts body)))))))
        (timbre/debugf "CHIME %s: All done!" id)))))

(defrecord Threadpool []
  component/Lifecycle
  (start [component]
    (assoc component :pool (cp/threadpool 100)))
  (stop [component]
    (when-let [{pool :pool} component]
      (cp/shutdown pool))
    (dissoc component :pool)))

(defrecord ServerChimer [db slack]
  component/Lifecycle
  (start [component]
    (assoc
     component
     :stop
     (chime-at
      (db/all-pings db)
      (chime! component))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))
    (when-let [threadpool (:threadpool component)]
      (cp/shutdown threadpool))
    (dissoc component :stop :threadpool)))
