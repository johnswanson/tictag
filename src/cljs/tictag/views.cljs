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
  []
  (let [meeting-query-per-day (subscribe [:meeting-query-per-day])
        tag-counts (subscribe [:sorted-tag-counts])]
    (fn []
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

(defn input [type]
  (fn [& {:keys [value change login]}]
    [:input
     {:value       value
      :type        type
      :on-change   #(change (.. % -target -value))
      :on-key-down #(condp = (.. % -which)
                      ENTER (login)
                      nil)}]))

(def username-input (input :text))
(def password-input (input :password))

(defn login [f]
  [:button {:on-click f}
   "Login"])

(defn signup [f]
  [:button {:on-click f}
   "Sign Up"])

(defn login-or-signup-form [& {:keys [username password login-fn signup-fn ch-username ch-password]}]
  [:div
   [:div
    [:div
     [:label "Username"
      [username-input
       :value username
       :change ch-username
       :login login-fn]]]
    [:div
     [:label "Password"
      [password-input
       :value password
       :change ch-password
       :login login-fn]]]]
   [login login-fn]
   [signup signup-fn]])

(defn app
  []
  (let [auth-token?   (subscribe [:auth-token])
        temp-username (subscribe [:login/username-input])
        temp-password (subscribe [:login/password-input])
        login-fn      #(dispatch [:login/submit-login])
        signup-fn     #(dispatch [:login/submit-signup])
        ch-password   #(dispatch [:login/password-input %])
        ch-username   #(dispatch [:login/username-input %])]
    (fn []
      (if @auth-token?
        [logged-in-app]
        [login-or-signup-form
         :username @temp-username
         :password @temp-password
         :login-fn login-fn
         :signup-fn signup-fn
         :ch-username ch-username
         :ch-password ch-password]))))

