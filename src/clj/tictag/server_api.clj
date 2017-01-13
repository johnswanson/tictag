(ns tictag.server-api
  (:require [tictag.db :as db]
            [clojure.java.jdbc :as j]
            [tictag.beeminder :as bm]))

(defn sleep [db-component & [pings]]
  (db/make-pings-sleepy! db-component (or pings (db/sleepy-pings db-component))))

(defn sleepy-pings [db-component]
  (db/sleepy-pings db-component))

(defn add-ping! [db-component long-time tags local-time]
  (db/add-tags db-component long-time tags local-time))

(defn update-ping! [db-component timestamp tags]
  (db/update-tags! db-component [{:tags tags :timestamp timestamp}]))

(defn beeminder-sync! [bm-config pings]
  (bm/sync! bm-config pings))

(defn beeminder-sync-from-db! [bm-config {db :db}]
  (beeminder-sync! bm-config (db/get-pings db)))

(defn pings [{db :db}]
  (db/get-pings db))

(defn add-ping-and-sync! [db-component long-time tags local-time bm-config]
  (let [pings (add-ping! db-component long-time tags local-time)]
    (beeminder-sync! bm-config pings)))

(defn last-ping [{db :db}]
  (let [pings (db/get-pings db ["select * from pings order by timestamp desc limit 1"])]
    (first pings)))
