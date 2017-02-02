(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [tictagapi.core :as tagtime]
            [clojure.java.jdbc :as j]
            [tictag.utils :as utils]
            [amalloy.ring-buffer :refer [ring-buffer]]))

(defrecord Database [db-spec tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debugf "Starting database, config: %s" (pr-str db-spec))
    (assoc component
           :db db-spec
           :pends (atom (ring-buffer 16))))
  (stop [component]
    (dissoc component :db)))

(defn insert-tag! [db long-time tags & [local-time]]
  (j/execute!
   db
   [(str/join " "
              ["INSERT INTO pings"
               "(\"ts\", \"tags\", \"local_time\")"
               "VALUES (?, ?, ?)"
               "ON CONFLICT (ts)"
               "DO UPDATE"
               "SET"
               "tags=EXCLUDED.tags,"
               "local_time=EXCLUDED.local_time"])
    long-time
    (str/join " " tags)
    (or local-time (utils/local-time-from-long long-time))]))

(defn add-pend! [rb id long-time]
  (into rb [[id long-time]]))

(defn add-pending! [{:keys [pends db]} long-time id]
  (insert-tag! db long-time ["afk"])
  (swap! pends add-pend! id long-time))

(defn pending-timestamp [{:keys [pends]} id]
  (second (first (filter #(= (first %) id) @pends))))

(defn local-day [local-time] (str/replace (subs local-time 0 10) #"-" ""))

(defn to-ping [{:keys [local_time ts tags]}]
  {:tags (set (map keyword (str/split tags #" ")))
   :local-time local_time
   :local-day (local-day local_time)
   :timestamp ts})

(defn get-pings [db & [query]]
  (map to-ping (j/query db (or query ["select * from pings order by ts"]))))

(defn add-tags [{db :db} long-time tags local-time]
  (j/with-db-connection [db-handle db]
    (insert-tag! db-handle long-time tags local-time)
    (get-pings db-handle)))

(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))

(defn update-tags-query [{:keys [tags timestamp]}]
  ["update pings set tags=? where ts=?" (str/join " " tags) timestamp])

(defn update-tags! [{db :db} pings]
  (doseq [ping pings]
    (j/execute! db (update-tags-query ping))))

(defn sleepy-pings
  "Return the most recent contiguous set of pings marked :afk in the database"
  [{db :db}]
  (->> ["select * from pings order by ts desc limit 100"]
       (get-pings db)
       (drop-while (comp not :afk :tags))
       (take-while (comp :afk :tags))))

(defn sleep [ping]
  (assoc ping :tags #{"sleep"}))

(defn make-pings-sleepy! [db pings]
  (update-tags! db (map sleep pings)))
