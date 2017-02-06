(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.events]
            [tictag.subs]
            [tictag.dates :refer [days-since-epoch seconds-since-midnight]]
            [cljs-time.core :as t]
            [cljs-time.format :as f])
  (:import [goog.date.Interval]))

(defn datapoint [ping]
  (let [active? (subscribe [:ping-active? ping])
        pixel   (subscribe [:ping-pixel ping])]
    (fn [ping]
      [:circle {:on-mouse-over #(js/console.log ping)
                :cx (:x @pixel)
                :cy (:y @pixel)
                :r 3
                :style {:opacity (if @active? "0.8" "0.2")}
                :fill  (if @active? "purple" "black")}])))

(defn matrix-plot []
  (let [width  (subscribe [:matrix-plot-width])
        height (subscribe [:matrix-plot-height])
        pings  (subscribe [:matrix-plot-pings])]
    (fn []
      [:svg {:style {:width (str @width "px") :height (str @height "px")}}
       [:g {:style {:stroke "black" :stroke-width 1}}
        [:line {:x1 0 :x2 0 :y1 0 :y2 @height}]
        [:line {:x1 0 :x2 @width :y1 @height :y2 @height}]]
       [:g
        (for [ping @pings]
          ^{:key (:local-time ping)}
          [datapoint ping])]])))

(defn app
  []
  (let [pings (subscribe [:pings])]
    (fn []
      [:div "Hello you"
       [:span {:on-click #(dispatch [:fetch-pings])} "Click Me"]
       [:input {:type :text
                :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]
       [matrix-plot]])))


