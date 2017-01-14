(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub]]))

(defn get-pings [db _]
  (if-let [query (:ping-query db)]
    (filter (fn [{:keys [tags]}]
              (tags (keyword query))) (:pings db []))
    (:pings db [])))

(reg-sub :pings get-pings)

(reg-sub :db first)

