(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub]]
            [cljs-time.format :as f]))

(def formatter (f/formatters :date-time))

(defn parse-date [ping]
  (assoc ping
         :local-time (f/parse formatter (:local-time ping))
         :old-local-time (:local-time ping)))

(defn pings [db] (map parse-date (:pings db [])))

(defn get-pings [db _]
  (if-let [query (:ping-query db)]
    (map (fn [{:keys [tags] :as ping}]
           (if (tags (keyword query))
             (assoc ping :active? true)
             (assoc ping :active? false)))
         (pings db))
    (pings db)))

(reg-sub :pings get-pings)

(reg-sub :db first)

