(ns tictag.server-api
  (:require [tictag.db :as db]
            [tictag.config :as config]
            [clojure.java.jdbc :as j]
            [tictag.beeminder :as bm]
            [reloaded.repl :refer [system]]))

(defn sleep [& [pings]]
  (db/make-pings-sleepy! (:db system) (or pings (db/sleepy-pings (:db system)))))

(defn sleepy-pings []
  (db/sleepy-pings (:db system)))

(defn add-ping! [long-time tags local-time]
  (db/add-tags (:db system) long-time tags local-time))

(defn update-ping! [timestamp tags]
  (db/update-tags! (:db system) [{:tags tags :timestamp timestamp}]))

(defn beeminder-sync! [pings]
  (bm/sync! config/beeminder pings))

(defn beeminder-sync-from-db! []
  (beeminder-sync! config/beeminder (db/get-pings (:db (:db system)))))

(defn pings []
  (db/get-pings (:db (:db system))))

(defn add-ping-and-sync! [long-time tags local-time]
  (let [pings (add-ping! (:db system) long-time tags local-time)]
    (beeminder-sync! config/beeminder pings)))

(defn last-ping []
  (let [pings (db/get-pings (:db (:db system)) ["select * from pings order by timestamp desc limit 1"])]
    (first pings)))
