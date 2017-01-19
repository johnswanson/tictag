(ns tictag.tagtime
  (:require [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clojure.java.io :as io]
            [clj-time.core :as t]))


(def IA 16807)
(def IM 2147483647)
(def FIRSTPING 1184083200)

(defn next-ran [seed]
  (rem (* IA seed) IM))

(defn next-ping [exprand prev-ping]
  (max (inc prev-ping)
       (Math/round (+ prev-ping exprand))))


(defn tagtime [gap seed]
  (let [next-ran   (fn [seed] (rem (* IA seed) IM))
        ran0s      (iterate next-ran (next-ran seed))
        ran01s     (map #(/ % IM) ran0s)
        exprands   (map #(* -1 gap (Math/log %)) ran01s)
        pings-unix (reductions
                    (fn [prev-ping exprand]
                      (next-ping exprand prev-ping))
                    FIRSTPING
                    exprands)
        pings-ms   (map #(* 1000 %) pings-unix)
        pings      (map tc/from-long pings-ms)]
    {:pings-unix pings-unix
     :pings-ms   pings-ms
     :pings      pings
     :gap        gap
     :seed       seed}))

(defn times-until [{:keys [pings]} now]
  (take-while #(t/after? now %) pings))

(defn is-ping? [tagtime long-time]
  (= long-time
     (last
      (take-while #(>= long-time %)
                  (:pings-ms tagtime)))))

