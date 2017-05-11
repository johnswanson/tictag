(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.constants :refer [ENTER]]
            [tictag.events]
            [tictag.subs]
            [tictag.nav :refer [route-for]]
            [tictag.dates :refer [days-since-epoch seconds-since-midnight]]
            [tictag.views.settings]
            [tictag.views.signup]
            [tictag.views.login]
            [tictag.views.common :refer [page]]
            [cljs-time.core :as t]
            [cljs-time.format :as f]
            [goog.string :as gstring]
            [goog.string.format])
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

(defn tag-table-row [tag]
  (let [my-count     (subscribe [:tag-count tag])
        tag-%        (subscribe [:tag-% tag])
        minutes      (subscribe [:minutes-for-tag tag])
        time-per-day (subscribe [:time-per-day-for-tag tag])]
    (fn [tag]
      [:tr
       [:td tag]
       [:td @my-count]
       [:td (gstring/format "%.1f%%" @tag-%)]
       [:td @time-per-day]])))

(defn logged-in-app
  []
  (let [meeting-query-per-day (subscribe [:meeting-query-per-day])
        tag-counts            (subscribe [:sorted-tag-counts])]
    (fn []
      [:div
       [:div
        [:input {:type      :text
                 :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]]
       [matrix-plot]
       [:div @meeting-query-per-day " minutes per day"]
       [:table
        {:style {:border "1px solid black"}}
        [:tbody
         [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
         (for [tag @tag-counts]
           ^{:key (pr-str tag)}
           [tag-table-row tag])]]
       [:button {:on-click #(dispatch [:logout])} "Logout"]])))



(defn app
  []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      [page
       (case @active-panel
         :signup    [tictag.views.signup/signup]
         :login     [tictag.views.login/login]
         :dashboard [logged-in-app]
         :settings  [tictag.views.settings/settings]
         ;; if :active-panel not set yet, just wait for pushy to initialize
         [:div])])))

