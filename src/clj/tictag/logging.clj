(ns tictag.logging
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.carmine :refer [carmine-appender]]
            [taoensso.timbre.appenders.core :refer [println-appender]]
            [com.stuartsierra.component :as component]))

(defn configure! []
  (timbre/merge-config!
   {:appenders {:carmine (assoc (carmine-appender) :async? true)}}))

