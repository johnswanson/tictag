(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.events]
            [tictag.subs]
            [cljs-time.core :as t]
            [cljs-time.format :as f]))

(defn datapoint [x y]
  [:circle {:cx x :cy y :r 5 :style {:opacity "0.2"}}])

(defn days-since [d1 d2]
  (Math/round
   (/ (- d2 d1)
      (* 24 60 60 1000))))

(defn day-range [dates]
  (let [earliest (t/earliest dates)
        latest   (t/latest dates)]
    [(days-since (t/epoch) (t/earliest dates))
     (days-since (t/epoch) (t/latest dates))]))

(defn time-range [& args] [0 (* 60 60 24)])

(defn seconds-since-midnight [date]
  (when date
    (/ (- date (t/at-midnight date))
       (* 1000))))

(defn pct-mapper [first-date last-date first-time last-time]
  (let [date-diff (- last-date first-date)
        secs-diff (- last-time first-time)]
    (fn [[days-since-epoch secs-since-midnight]]
      [(/ (- days-since-epoch first-date) date-diff)
       (/ (- secs-since-midnight first-time) secs-diff)])))

(defn to-days+secs [ping]
  (let [datetime (:local-time ping)]
    [(days-since (t/epoch) datetime)
     (seconds-since-midnight datetime)]))

(defn graph [width height pings]
  [:svg {:style {:width (str width "px") :height (str height "px")}}
   [:g {:style {:stroke "black" :stroke-width 1}}
    [:line {:x1 0 :x2 0 :y1 0 :y2 height}]
    [:line {:x1 0 :x2 width :y1 height :y2 height}]]
   [:g
    (when (seq pings)
      (let [[first-date last-date] (day-range (map :local-time pings))
            [first-time last-time] (time-range (map :local-time pings))
            days+secs  (map to-days+secs pings)
            pct-mapper (pct-mapper first-date last-date first-time last-time)
            pcts       (map pct-mapper days+secs)]
        (for [[pct-x pct-y] pcts]
          ^{:key (str pct-x pct-y)}
          [datapoint (* width pct-x) (* height pct-y)])))]])

(defn app
  []
  (let [pings (subscribe [:pings])]
    (fn []
      [:div "Hello you"
       [:span {:on-click #(dispatch [:fetch-pings])} "Click Me"]
       [:input {:type :text
                :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]
       [graph 500 500 @pings]])))

