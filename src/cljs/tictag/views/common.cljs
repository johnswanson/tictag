(ns tictag.views.common
  (:require [re-frame.core :refer [dispatch subscribe]]
            [tictag.nav :refer [route-for]]))

(defn login-link [] [:a {:href (route-for :login)} "Login"])
(defn signup-link [] [:a {:href (route-for :signup)} "Signup"])
(defn logout-link [] [:a {:href (route-for :logout)} "Log Out"])

(defn- links []
  (let [user (subscribe [:authorized-user])]
    (fn links []
      [:div {}
       (when-not @user [login-link])
       (when-not @user [signup-link])
       (when @user [logout-link])])))

(defn- nav []
  [:nav
   [:span.logo "TagTime"]
   [links]])

(defn page [& content]
  [:div
   {}
   [nav]
   content])

