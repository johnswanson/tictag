(ns tictag.utils
  (:require [com.stuartsierra.component :as component]
            [clj-time.local]
            [clj-time.format :as f]
            [clj-time.core :as t]))

(defn local-time [long-time]
  (clj-time.local/format-local-time
   (t/to-time-zone long-time (t/default-time-zone))
   :date-hour-minute-second))

(defn system-map [m]
  (apply component/system-map
         (flatten (into [] m))))
