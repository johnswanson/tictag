(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.constants :refer [ENTER]]
            [tictag.events]
            [tictag.subs]
            [tictag.nav :refer [route-for]]
            [tictag.dates :refer [weeks-since-epoch days-since-epoch seconds-since-midnight]]
            [tictag.views.settings]
            [tictag.views.signup]
            [tictag.views.inputs]
            [tictag.views.login]
            [tictag.views.about]
            [tictag.views.common :refer [page]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as f]
            [goog.string :as gstring]
            [goog.string.format]
            [taoensso.timbre :as timbre
             :refer-macros [debug]]
            [c2.scale]
            [c2.svg]
            [re-com.core :as re-com])
  (:import [goog.date.Interval]))

(def simple-formatter (f/formatter "MM-dd"))

(defn format-day-to-time [day]
  (let [in-seconds (* day 24 60 60 1000)]
    (f/unparse simple-formatter (tc/from-long in-seconds))))

(defn circle-for-ping [{:keys [active?]}]
  [:ellipse {:rx    2
             :ry    12
             :style (if active?
                      {:opacity "0.6"}
                      {:opacity "0.1"})
             :fill  (if active? "#cc0000" "black")}])

(declare matrix-plot-view)

(defn matrix-plot []
  (let [width   (subscribe [:matrix-plot-width])
        height  (subscribe [:matrix-plot-height])
        min-day (subscribe [:min-ping-day])
        max-day (subscribe [:max-ping-day])]
    (matrix-plot-view @width @height @min-day @max-day)))

(defn time-axis [yscale margin]
  [:g {:transform (c2.svg/translate [margin 0])}
   (c2.svg/axis yscale
             (range 0 (* 24 60 60) (* 60 60))
             :orientation :left
             :text-margin 48
             :formatter #(str (/ % 60 60) ":00"))])

(defn hist-axis [density-yscale width margin]
  [:g {:transform (c2.svg/translate [(- width margin) 0])}
   (c2.svg/axis density-yscale
             (range 0 25)
             :text-margin 14
             :orientation :right)])

(defn days-axis [xscale min-day max-day height margin]
  [:g {:transform (c2.svg/translate [0 (- height margin)])}
   (c2.svg/axis
    xscale
    (range min-day max-day 30)
    :orientation :bottom
    :text-margin 16
    :formatter format-day-to-time
    :label "Day")])

(defn axes [xscale yscale density-yscale width height margin min-day max-day]
  [:g {:style       {:stroke       "black"
                     :stroke-width 1
                     :font-weight  "100"}
       :font-size   "14px"
       :font-family "sans-serif"}
   [time-axis yscale margin]
   [hist-axis density-yscale width margin]
   [days-axis xscale min-day max-day height margin]])

(defn histogram [xscale density-yscale height margin]
  (let [day-totals (subscribe [:day-totals])]
    [:g
     (doall
      (for [[d freq] @day-totals]
        ^{:key d}
        [:rect
         {:x      (xscale d)
          :y      (- (density-yscale freq) margin)
          :height (- height (density-yscale freq))
          :style  {:opacity "0.2"}
          :fill   "#0000cc"
          :width  5}]))]))

(defn matrix [xscale yscale]
  (let [pings (subscribe [:pings])]
    [:g
     (doall
      (for [ping @pings]
        ^{:key (:timestamp ping)}
        [:g {:transform (c2.svg/translate [(xscale (:days-since-epoch ping))
                                           (yscale (:seconds-since-midnight ping))])}
         [circle-for-ping ping]]))]))

(defn matrix-plot-view [width height min-day max-day]
  (let [margin         50
        xscale         (c2.scale/linear :domain [min-day max-day]
                                        :range [margin (- width margin)])
        yscale         (c2.scale/linear :domain [0 (* 24 60 60)]
                                        :range [margin (- height margin)])
        density-yscale (c2.scale/linear :domain [0 24]
                                        :range [(- height margin) margin])]
    [:svg {:style {:width (str width "px") :height (str height "px")}}
     [axes xscale yscale density-yscale width height margin min-day max-day]
     [:g
      [histogram xscale density-yscale height margin]
      [matrix xscale yscale]]]))

(defn tag-table-row [tag]
  (let [my-count     (subscribe [:tag-count tag])
        tag-%        (subscribe [:tag-% tag])
        minutes      (subscribe [:minutes-for-tag tag])
        time-per-day (subscribe [:time-per-day-for-tag tag])]
    [:tr
     [:td tag]
     [:td @my-count]
     [:td (gstring/format "%.1f%%" @tag-%)]
     [:td @time-per-day]]))

(defn logged-in-app
  []
  (let [meeting-query-per-day (subscribe [:meeting-query-per-day])
        tag-counts            (subscribe [:sorted-tag-counts])]
    [:div
     [matrix-plot]
     [:div
      [re-com/input-text
       :style {:border-radius "0px"}
       :width "100%"
       :placeholder "Query"
       :model (reagent/atom "")
       :change-on-blur? false
       :on-change #(dispatch [:update-ping-query %])]]
     [:div (.toFixed @meeting-query-per-day 1) " minutes per day"]
     [:table
      {:style {:border "1px solid black"}}
      [:tbody
       [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
       (for [tag @tag-counts]
         ^{:key (pr-str tag)}
         [tag-table-row tag])]]]))



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
         :about     [tictag.views.about/about]
         ;; if :active-panel not set yet, just wait for pushy to initialize
         [:div])])))

