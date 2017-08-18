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

(defn tag-table-row [tag]
  (let [count        (subscribe [:tag-count tag])
        tag-%        (subscribe [:tag-% tag])
        minutes      (subscribe [:minutes-for-tag tag])
        active?      (subscribe [:tag-active? tag])
        time-per-day (subscribe [:time-per-day-for-tag tag])]
    [tag-table-row-view tag @count @tag-% @minutes @active? @time-per-day]))

(defn query-row [query]
  (let [count        (subscribe [:count-meeting-query])
        tag-%        (subscribe [:query-%])
        minutes      (subscribe [:minutes-meeting-query])
        time-per-day (subscribe [:meeting-query-per-day])]
    [tag-table-row-view query @count @tag-% @minutes true @time-per-day]))

(defn logged-in-app
  []
  (let [tag-counts  (subscribe [:sorted-tag-counts])
        ping-query  (subscribe [:ping-query])
        graph-src   (subscribe [:query-graph])
        window-size (subscribe [:window-size])]
    [re-com/v-box
     :gap "1em"
     :align :center
     :style {:max-width "90%"}
     :children
     [[re-com/input-text
       :style {:border-radius "0px"
               :max-width "100%"}
       :width "100%"
       :placeholder "Query"
       :model (reagent/atom "")
       :change-on-blur? false
       :on-change #(dispatch [:debounced-update-ping-query %])]
      [re-com/box
       :child [:img {:src    @graph-src
                     :width  "70%"
                     :style {:margin :auto}}]]
      [re-com/box
       :child
       [:table
        {:style {:border "1px solid black"}}
        [:tbody
         [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
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

