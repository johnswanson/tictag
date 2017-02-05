(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [cljs-time.format :as f]))

(def formatter (f/formatters :date-time))

(defn parse-date [ping]
  (assoc ping
         :local-time (f/parse formatter (:local-time ping))
         :str-local-time (:local-time ping)))

(reg-sub :raw-pings (fn [db _] (:pings db [])))

(reg-sub :parsed-pings
         (fn [_ _] (subscribe [:raw-pings]))
         (fn [raw-pings _]
           (map parse-date raw-pings)))

(reg-sub :ping-query (fn [db _] (:ping-query db)))

(reg-sub :query-fn
         (fn [_ _] (subscribe [:ping-query]))
         (fn [ping-query]
           (if ping-query
             (fn [{:keys [tags]}]
               (tags (keyword ping-query)))
             (constantly false))))

(reg-sub
 :pings
 (fn [_ _]
   [(subscribe [:parsed-pings])
    (subscribe [:query-fn])])
 (fn [[parsed-pings query] _]
   (map #(assoc % :active? (query %)) parsed-pings)))

(reg-sub :db first)

