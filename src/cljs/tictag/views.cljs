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
            [tictag.views.editor]
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

(def simple-formatter (f/formatter "yyyy-MM-dd"))

(defn format-day-to-time [day]
  (let [in-seconds (* day 24 60 60 1000)]
    (f/unparse simple-formatter (tc/from-long in-seconds))))

(defn circle-for-ping [ping]
  [:ellipse {:rx    2
             :ry    2
             :style {:opacity "0.5"}
             :fill  "#396AB1"}])

(declare matrix-plot-view)

(defn matrix-plot []
  (let [width   (subscribe [:matrix-plot-width])
        height  (subscribe [:matrix-plot-height])
        min-day (subscribe [:min-ping-day])
        max-day (subscribe [:max-ping-day])
        count   (subscribe [:count-meeting-query])]
    (matrix-plot-view @count @width @height @min-day @max-day)))

(defn time-axis [yscale]
  [:g {:style {:stroke "#396AB1"}}
   (c2.svg/axis yscale
                (range 0 (* 24 60 60) (* 4 60 60))
                :orientation :right
                :formatter #(str (/ % 60 60) ":00"))])

(defn hist-axis [density-yscale]
  [:g {:style {:stroke "#DA7C30"}}
   (c2.svg/axis density-yscale
                [0 8 16 24]
                :orientation :left
                :text-margin 25
                :label-margin 40
                :label "Hours (per day)")])

(defn days-axis [xscale]
  (c2.svg/axis
   xscale
   (let [[r0 r1] (:domain xscale)]
     (butlast (range r0 r1 (js/Math.round (/ (- r1 r0) 10)))))
   :orientation :bottom
   :text-margin 25
   :label-margin 43
   :major-tick-width 12
   :formatter format-day-to-time
   :label "Date"))

(defn cum-axis [yscale]
  [:g {:style {:stroke "#3E9651"}}
   (c2.svg/axis
    yscale
    (timbre/spy (conj (:ticks (c2.ticks/search (:domain yscale))) (second (:domain yscale))))
    :orientation :right
    :label "Hours (cum)")])

(defn axes [xscale yscale density-yscale count-scale width height margin min-day max-day]
  [:g {:style       {:stroke       "black"
                     :stroke-width 1
                     :font-weight  "100"}
       :font-size   "14px"
       :font-family "sans-serif"}
     [:g
      [:g {:transform (c2.svg/translate [(- width margin) 0])}
       [cum-axis count-scale]]
      [:g {:transform (c2.svg/translate [margin 0])}
       [hist-axis density-yscale]]]
   [:g {:transform (c2.svg/translate [(/ width 2) 0])}
    [time-axis yscale]]
   [:g {:transform (c2.svg/translate [0 (- height margin)])}
    [days-axis xscale]]])

(defn histogram [xscale density-yscale height margin]
  (let [day-totals (subscribe [:day-totals])]
    [:g {:style {:fill    "#DA7C30"
                 :opacity "0.8"}}
     (doall
      (for [[d freq] @day-totals]
        (let [hours  (* freq 0.75)
              scaled (density-yscale hours)]
          ^{:key d}
          [:rect
           {:x      (xscale d)
            :y      scaled
            :height (- height scaled margin)
            :width  1}])))]))

(defn matrix [xscale yscale]
  (let [pings (subscribe [:pings])]
    [:g
     (doall
      (for [ping @pings]
        (when (:active? ping)
          ^{:key (:ping/id ping)}
          [:g {:transform (c2.svg/translate [(xscale (:ping/days-since-epoch ping))
                                             (yscale (:ping/seconds-since-midnight ping))])}
           [circle-for-ping ping]])))]))

(defn cumulative [xscale yscale width height margin]
  (let [totals          (subscribe [:day-cum-totals])]
    (fn [xscale yscale width height margin]
      (when @totals
        [:g
         [:g {:style {:fill :none
                      :stroke "#3E9651"
                      :stroke-width "1"
                      :opacity "0.8"}}
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
        count-scale    (c2.scale/linear :domain [0 (js/Math.ceil (* 0.75 count))]
                                        :range [(- height margin) margin])]
    (if-not (= (:domain count-scale) [0 0])
      [:svg {:style {:width (str width "px") :height (str height "px")
                     :background-color "#eee"
                     :border "1px solid black"}}
       [axes xscale yscale density-yscale count-scale width height margin min-day max-day]
       [:g
        ^{:key "hist"} [histogram xscale density-yscale height margin]
        ^{:key "matrix"} [matrix xscale yscale]
        ^{:key "cumulative"} [cumulative xscale count-scale width height margin]]]
      [:svg {:style {:width (str width "px") :height "1px"}}])))

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
     :gap "1em"
     :align :center
     :children
     [[re-com/input-text
       :style {:border-radius "0px"}
       :width "100%"
       :placeholder "Query"
       :model (reagent/atom "")
       :change-on-blur? false
       :on-change #(dispatch [:debounced-update-ping-query %])]
      [matrix-plot]
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
         :editor    [tictag.views.editor/editor]
         ;; if :active-panel not set yet, just wait for pushy to initialize
         [:div])])))

