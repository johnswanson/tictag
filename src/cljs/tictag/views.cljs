(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.constants :refer [ENTER]]
            [tictag.events]
            [tictag.subs]
            [tictag.nav :refer [route-for]]
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
  (let [auth-token            (subscribe [:auth-token])
        meeting-query-per-day (subscribe [:meeting-query-per-day])
        tag-counts            (subscribe [:sorted-tag-counts])]
    (fn []
      (if-not @auth-token
        (dispatch [:redirect-to-page :login]) ;; this seems bad...
        [:div
         [:input {:type      :text
                  :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]
         [matrix-plot]
         [:div @meeting-query-per-day " minutes per day"]
         [:table
          {:style {:border "1px solid black"}}
          [:tbody
           [:tr [:th "Tag"] [:th "Count"] [:th "Percent of Pings"] [:th "Time Per Day"]]
           (for [tag @tag-counts]
             ^{:key (pr-str tag)}
             [tag-table-row tag])]]
         [:button {:on-click #(dispatch [:logout])} "Logout"]]))))

(defn input [type]
  (fn [& {:keys [value change submit]}]
    [:input
     {:value       value
      :type        type
      :on-change   #(change (.. % -target -value))
      :on-key-down #(condp = (.. % -which)
                      ENTER (submit)
                      nil)}]))

(def username-input (input :text))
(def email-input (input :text))
(def password-input (input :password))
(def tz-input (input :text))

(defn login-button [f]
  [:button {:on-click f} "Login"])

(defn signup-button [f]
  [:button {:on-click f}
   "Sign Up"])

(defn signup-form [& {:keys [username password email timezone signup-fn ch-username ch-password ch-email ch-timezone]}]
  [:div
   [:div
    [:div
     [:label "Username"
      [username-input
       :value username
       :change ch-username
       :submit signup-fn]]]
    [:div
     [:label "Email Address"
      [email-input
       :value email
       :change ch-email
       :submit signup-fn]]]
    [:div
     [:label "Password"
      [password-input
       :value password
       :change ch-password
       :submit signup-fn]]]
    [:div
     [:label "Time Zone (e.g. \"America/Los_Angeles)"
      [tz-input
       :value timezone
       :change ch-timezone
       :submit signup-fn]]]]
   [signup-button signup-fn]
   [:a {:href (route-for :login)} "Login"]])

(defn login-form [& {:keys [username password login-fn ch-username ch-password]}]
  [:div
   [:div
    [:div
     [:label "Username"
      [username-input
       :value username
       :change ch-username
       :submit login-fn]]]
    [:div
     [:label "Password"
      [password-input
       :value password
       :change ch-password
       :submit login-fn]]]]
   [login-button login-fn]
   [:a {:href (route-for :signup)} "Signup"]])

(defn login []
  (let [temp-username (reagent/atom nil)
        temp-password (reagent/atom nil)
        login-fn      #(dispatch [:login/submit-login {:username @temp-username
                                                       :password @temp-password}])
        ch-password   #(reset! temp-password %)
        ch-username   #(reset! temp-username %)]
    (fn []
      [login-form
       :username @temp-username
       :password @temp-password
       :login-fn login-fn
       :ch-username ch-username
       :ch-password ch-password])))

(defn signup []
  (let [temp-username (reagent/atom nil)
        temp-password (reagent/atom nil)
        temp-email    (reagent/atom nil)
        temp-timezone (reagent/atom nil)
        signup-fn     #(dispatch [:login/submit-signup {:username @temp-username
                                                        :password @temp-password
                                                        :email    @temp-email
                                                        :tz       @temp-timezone}])
        ch-password   #(reset! temp-password %)
        ch-timezone   #(reset! temp-timezone %)
        ch-email      #(reset! temp-email %)
        ch-username   #(reset! temp-username %)]
    (fn []
      [signup-form
       :username @temp-username
       :password @temp-password
       :timezone @temp-timezone
       :email @temp-email
       :timezone @temp-timezone
       :signup-fn signup-fn
       :ch-username ch-username
       :ch-email ch-email
       :ch-password ch-password
       :ch-timezone ch-timezone])))

(defn app
  []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      (case @active-panel
        :signup    [signup]
        :login     [login]
        :dashboard [logged-in-app]
        ;; if :active-panel not set yet, just wait for pushy to initialize
        [:div]))))

