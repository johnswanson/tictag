(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [tictag.beeminder :as beeminder]
            [tictag.config :as config :refer [config]]))

(defrecord Database [file]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting database")
    (assoc component :db
           (atom {:pends {}})))
  (stop [component]
    (dissoc component :db)))

(defn add-pending! [{:keys [db]} long-time id]
  (swap! db assoc-in [:pends id] long-time))

(defn pending-timestamp [db id]
  (get-in @(:db db) [:pends id]))

(defn spit-tags [file long-time tags local-time]
  (spit file (format "%d,%s,%s\n" long-time (str/join " " tags) local-time) :append true))

(defn add-tags [{file :file} long-time tags local-time]
  (spit-tags file long-time tags local-time)
  (beeminder/sync! {:auth-token (:beeminder-auth-token config)}
                   (:beeminder-user config)
                   (:beeminder-goals config)
                   (csv/read-csv (io/reader file))))


