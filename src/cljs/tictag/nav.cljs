(ns tictag.nav
  (:require [tictag.routes :refer [app-routes]]
            [pushy.core :as pushy]
            [bidi.bidi :as bidi]
            [re-frame.core :refer [dispatch]]))

(defn set-page! [match] (dispatch [:set-current-page match]))

(def history
  (pushy/pushy set-page! (partial bidi/match-route app-routes)))

(defn set-token! [route] (pushy/set-token! history (bidi/path-for app-routes route)))
(defn replace-token! [route] (pushy/replace-token! history (bidi/path-for app-routes route)))

(defn route-for [& args] (apply bidi/path-for app-routes args))

(defn start! [] (pushy/start! history))

