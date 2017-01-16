(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format]]
            [cljs-time.format :as f]))

(def formatter (f/formatters :date-hour-minute-second))

(defn parse-date [ping]
  (update ping :local-time #(f/parse formatter %)))

(reg-event-fx
 :fetch-pings
 (fn [{:keys [db]} _]
   {:db         (assoc db :fetching true)
    :http-xhrio {:method          :get
                 :uri             "/pings"
                 :timeout         8000
                 :response-format (transit-response-format {})
                 :on-success      [:good-http-result]
                 :on-failure      [:bad-http-result]}}))

(reg-event-db
 :good-http-result
 (fn [db [_ result]]
   (assoc db :pings (map parse-date result))))

(reg-event-db
 :bad-http-result
 (fn [db [_ result]]
   (timbre/errorf "BAD RESULT: %s" (pr-str (keys result)))))

(reg-event-db
 :update-ping-query
 (fn [db [_ v]]
   (if (seq v)
     (assoc db :ping-query v)
     (assoc db :ping-query nil))))
