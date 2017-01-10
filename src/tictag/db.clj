(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [tictag.beeminder :as beeminder]
            [tictag.config :as config :refer [config]]
            [tictag.tagtime :as tagtime]
            [clojure.java.jdbc :as j]
            [tictag.utils :as utils]))

(defn table-exists? [db name]
  (seq
   (j/query db ["select name from sqlite_master where type='table' and name=?" name])))

(defn create-pings! [db]
  (j/execute! db
              [(j/create-table-ddl :pings
                                   [[:timestamp :integer
                                     :primary :key]
                                    [:tags :text]
                                    [:local_time :text]])]))
(defrecord Database [file tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting database")
    (let [db-spec {:dbtype "sqlite" :dbname file}]
      (when-not (table-exists? db-spec "pings")
        (timbre/debug "Pings table does not exist, creating...")
        (create-pings! db-spec))
      (assoc component
             :db db-spec
             :pends (atom {}))))
  (stop [component]
    (dissoc component :db)))

(defn insert-tag! [db long-time tags & [local-time]]
  (j/execute!
   db
   ["insert or replace into pings (\"timestamp\", \"tags\", \"local_time\") VALUES (?, ?, ?)"
    long-time
    (str/join " " tags)
    (or local-time (utils/local-time long-time))]))

(defn add-pending! [{:keys [pends db]} long-time id]
  (insert-tag! db long-time ["afk"])
  (swap! pends assoc id long-time))

(defn pending-timestamp [{:keys [pends]} id]
  (get @pends id))

(defn local-day [local-time] (str/replace (subs local-time 0 10) #"-" ""))

(defn to-ping [{:keys [local_time timestamp tags]}]
  {:tags (set (map keyword (str/split tags #" ")))
   :local-time local_time
   :local-day (local-day local_time)
   :timestamp timestamp})

(defn get-pings [db]
  (map to-ping (j/query db ["select * from pings"])))

(defn add-tags [{db :db} long-time tags local-time]
  (insert-tag! db long-time tags local-time)
  (beeminder/sync! {:auth-token (:beeminder-auth-token config)}
                   (:beeminder-user config)
                   (:beeminder-goals config)
                   (get-pings db)))


(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))
