(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub]]))

(defn get-pings [db _]
  (if-let [query (:ping-query db)]
    (map (fn [{:keys [tags] :as ping}]
           (if (tags (keyword query))
             (assoc ping :active? true)
             (assoc ping :active? false)))
         (:pings db []))
    (:pings db)))

(reg-sub :pings get-pings)

(reg-sub :db first)

