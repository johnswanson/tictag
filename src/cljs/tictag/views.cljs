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
            [c2.ticks]
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
        max-day (subscribe [:max-ping-day])
        count   (subscribe [:count-meeting-query])]
    (matrix-plot-view @count @width @height @min-day @max-day)))

(defn time-axis [yscale]
  (c2.svg/axis yscale
               (range 0 (* 24 60 60) (* 60 60))
               :orientation :right
               :formatter #(str (/ % 60 60) ":00")))

(defn hist-axis [density-yscale]
  (c2.svg/axis density-yscale
               (range 0 25)
               :orientation :left
               :label "Hours (per day)"))

(defn days-axis [xscale]
  (c2.svg/axis
   xscale
   (let [[r0 r1] (:domain xscale)]
     (range r0 r1 30))
   :orientation :bottom
   :formatter format-day-to-time
   :label "Day"))

(defn cum-axis [yscale]
  (c2.svg/axis
   yscale
   (conj (:ticks (c2.ticks/search (:domain yscale))) (second (:domain yscale)))
   :orientation :right
   :label "Hours (cum)"))

(defn axes [xscale yscale density-yscale count-scale width height margin min-day max-day]
  [:g {:style       {:stroke       "black"
                     :stroke-width 1
                     :font-weight  "100"}
       :font-size   "14px"
       :font-family "sans-serif"}
   (when-not (= (:domain count-scale) [0 0])
     [:g
      [:g {:transform (c2.svg/translate [(- width margin) 0])}
       [cum-axis count-scale]]
      [:g {:transform (c2.svg/translate [margin 0])}
       [hist-axis density-yscale]]])
   [:g {:transform (c2.svg/translate [(/ width 2) 0])}
    [time-axis yscale]]
   [:g {:transform (c2.svg/translate [0 (- height margin)])}
    [days-axis xscale]]])

(defn histogram [xscale density-yscale height margin]
  (let [day-totals (subscribe [:day-totals])]
    [:g
     (doall
      (for [[d freq] @day-totals]
        (let [hours  (* freq 0.75)
              scaled (density-yscale hours)]
          ^{:key d}
          [:rect
           {:x      (xscale d)
            :y      scaled
            :height (- height scaled margin)
            :style  {:opacity "0.2"}
            :fill   "#0000cc"
            :width  5}])))]))

(defn matrix [xscale yscale]
  (let [pings (subscribe [:pings])]
    [:g
     (doall
      (for [ping @pings]
        ^{:key (:timestamp ping)}
        [:g {:transform (c2.svg/translate [(xscale (:days-since-epoch ping))
                                           (yscale (:seconds-since-midnight ping))])}
         [circle-for-ping ping]]))]))

(defn cumulative [xscale yscale width height margin]
  (let [totals          (subscribe [:day-cum-totals])]
    (fn [xscale yscale width height margin]
      (when @totals
        [:g
         [:g {:style {:fill :none
                      :stroke "black"
                      :stroke-width "3"
                      :opacity "0.5"}}
          (c2.svg/line
           (map
            (fn [[day total]]
              [(xscale day) (yscale total)])
            @totals))]]))))


(defn matrix-plot-view [count width height min-day max-day]
  (let [margin         60
        xscale         (c2.scale/linear :domain [min-day max-day]
                                        :range [margin (- width margin)])
        yscale         (c2.scale/linear :domain [0 (* 24 60 60)]
                                        :range [margin (- height margin)])
        density-yscale (c2.scale/linear :domain [0 24]
                                        :range [(- height margin) margin])
        count-scale    (c2.scale/linear :domain [0 (js/Math.round (* 0.75 count))]
                                        :range [(- height margin) margin])]
    [:svg {:style {:width (str width "px") :height (str height "px")}}
     [axes xscale yscale density-yscale count-scale width height margin min-day max-day]
     [:g
      [histogram xscale density-yscale height margin]
      [matrix xscale yscale]
      [cumulative xscale count-scale width height margin]]]))

(defn tag-table-row-view [tag count tag-% minutes active? time-per-day]
  [:tr (if active?
         {:style {:background-color "#333"
                  :color            "#ddd"}}
         {:style {:background-color "#ddd"
                  :color            "#333"}})
   [:td tag]
   [:td count]
   [:td (gstring/format "%.1f%%" tag-%)]
   [:td time-per-day]])

(defn query-row [query]
  (let [count        (subscribe [:count-meeting-query])
        tag-%        (subscribe [:query-%])
        minutes      (subscribe [:minutes-meeting-query])
        time-per-day (subscribe [:meeting-query-per-day])]
    [tag-table-row-view query @count @tag-% @minutes true @time-per-day]))

(defn tag-table-row [tag]
  (let [count        (subscribe [:tag-count tag])
        tag-%        (subscribe [:tag-% tag])
        minutes      (subscribe [:minutes-for-tag tag])
        active?      (subscribe [:tag-active? tag])
        time-per-day (subscribe [:time-per-day-for-tag tag])]
    [tag-table-row-view tag @count @tag-% @minutes @active? @time-per-day]))

(defn logged-in-app
  []
  (let [tag-counts            (subscribe [:sorted-tag-counts])
        ping-query            (subscribe [:ping-query])]
    [re-com/v-box
     :align :center
     :children
     [[matrix-plot]
      [re-com/input-text
       :style {:border-radius "0px"}
       :width "100%"
       :placeholder "Query"
       :model (reagent/atom "")
       :change-on-blur? false
       :on-change #(dispatch [:debounced-update-ping-query %])]
      [re-com/box
       :child
       [:table
        {:style {:border "1px solid black"}}
        [:tbody
         [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
         (when @ping-query [query-row @ping-query])
         (for [tag @tag-counts]
           ^{:key (pr-str tag)}
           [tag-table-row tag])]]]]]))

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

