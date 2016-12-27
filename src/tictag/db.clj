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

(defn add-tags' [db long-time tags & [local-time]]
  (-> db
      (assoc-in [:pings long-time :tags] tags)
      (assoc-in [:pings long-time :local-time] local-time)))


(defn add-tags [db long-time tags & [local-time]]
  (e/swap! db add-tags' long-time tags local-time))
