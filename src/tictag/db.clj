(ns tictag.db
  (:require [tictag.config :refer [config]]
            [alandipert.enduro :as e]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]))

(defrecord Database []
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting database")
    (assoc component :db
           (e/file-atom {:pings {}}
                        (:server-db-file config))))
  (stop [component]
    (dissoc component :db)))

(defn add-pending! [db long-time id]
  (e/swap!
   db
   (fn [db]
     (-> db
         (assoc-in [:pends id] long-time)
         (update-in [:pings long-time] identity)))))

(defn pending-timestamp [db id]
  (get-in @db [:pends id]))

(defn add-tags' [db long-time tags & [local-time]]
  (-> db
      (assoc-in [:pings long-time]
                {:tags       tags
                 :timestamp  long-time
                 :local-time local-time})))


(defn add-tags [db long-time tags & [local-time]]
  (e/swap! db add-tags' long-time tags local-time))
