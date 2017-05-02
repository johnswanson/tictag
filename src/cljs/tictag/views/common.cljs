(ns tictag.views.common
  (:require [re-frame.core :refer [dispatch]]
            [tictag.nav :refer [route-for]]))

(defn login-link [] [:a {:href (route-for :login)} "Login"])
(defn signup-link [] [:a {:href (route-for :signup)} "Signup"])
(defn logout-link [] [:a {:href (route-for :logout)} "Log Out"])

(defn- links []
  [:div {}
   [login-link]
   [signup-link]
   [logout-link]])

(defn- nav []
  [:nav
   [:span.logo "TagTime"]
   [links]])

(defn page [& content]
  [:div
   {}
   [nav]
   content])

