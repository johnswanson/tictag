(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.constants :refer [ENTER]]
            [tictag.events]
            [tictag.subs]
            [tictag.dates :refer [days-since-epoch seconds-since-midnight]]
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
       [:td (pr-str tag)]
       [:td @my-count]
       [:td (gstring/format "%.1f%%" @tag-%)]
       [:td @time-per-day]])))
(defn logged-in-app
  [authenticated-user]
  (let [meeting-query-per-day (subscribe [:meeting-query-per-day])
        tag-counts (subscribe [:sorted-tag-counts])]
    (fn [authenticated-user]
      [:div
       [:span {:on-click #(dispatch [:fetch-pings])
               :style {:cursor :pointer}} "Click Me"]
       [:input {:type :text
                :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]
       [matrix-plot]
       [:div @meeting-query-per-day " minutes per day"]
       [:table
        {:style {:border "1px solid black"}}
        [:tbody
         [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
         (for [tag @tag-counts]
           ^{:key (pr-str tag)}
           [tag-table-row tag])]]])))

(defn username-input []
  (let [username (subscribe [:login/username-input])]
    (fn []
      [:input
       {:value     @username
        :on-change #(dispatch [:login/username-input (.. % -target -value)])
        :on-key-down #(condp = (.. % -which)
                        ENTER (dispatch [:login/submit-login])
                        nil)}])))

(defn password-input []
  (let [password (subscribe [:login/password-input])]
    (fn []
      [:input
       {:type      :password
        :value     @password
        :on-change #(dispatch [:login/password-input (.. % -target -value)])
        :on-key-down #(condp = (.. % -which)
                        ENTER (dispatch [:login/submit-login])
                        nil)}])))

(defn login []
  [:button {:on-click #(dispatch [:login/submit-login])}
   "Login"])

(defn signup []
  [:button {:on-click #(dispatch [:login/submit-signup])}
   "Sign Up"])

(defn login-or-signup-form []
  [:div
   [username-input]
   [password-input]
   [login] [signup]])

(defn logged-out-app []
  [login-or-signup-form])

(defn app
  []
  (let [has-token? (subscribe [:auth-token])]
    (fn []
      (if @has-token?
        [logged-in-app has-token?]
        [logged-out-app]))))

