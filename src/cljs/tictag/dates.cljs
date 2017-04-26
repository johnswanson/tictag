(ns tictag.dates
  (:require [cljs-time.core :as t]))

(defn hours [date] (t/hour date))
(defn minutes [date] (t/minute date))
(defn seconds [date] (t/second date))

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

