(ns tictag.dates
  (:require [cljs-time.core :as t]))

(defn to-int [s]
  (let [res (js/parseInt s 10)]
    (when (= res NaN)
      (throw (js/Error. (str "Invalid int: " s))))
    res))

(defn hours [date-str] (to-int (subs date-str 11 13)))
(defn minutes [date-str] (to-int (subs date-str 14 16)))
(defn seconds [date-str] (to-int (subs date-str 17 19)))

(defn days-since [d1 d2]
  (Math/round
   (/ (- d2 d1)
      (* 24 60 60 1000))))

(defn days-since-epoch [date]
  (days-since (t/epoch) date))

(defn seconds-since-midnight [date]
  (+ (seconds date)
     (* 60 (minutes date))
     (* 60 60 (hours date))))

