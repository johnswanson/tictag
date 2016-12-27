(ns tictag.tagtime
  (:require [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clojure.java.io :as io]))

(def gap (* 30 60))
(def FIRSTSEED 666)
(def IA 16807)
(def IM 2147483647)
(def FIRSTPING 1184083200)

(defn next-ran [seed]
  (rem (* IA seed) IM))

(def ran0s (iterate next-ran (next-ran 666)))
(def ran01s (map #(/ % IM) ran0s))
(def exprands (map #(* -1 gap (Math/log %)) ran01s))

(defn next-ping [exprand prev-ping]
  (max (inc prev-ping)
       (Math/round (+ prev-ping exprand))))

(def pings-unix
  (reductions (fn [prev-ping exprand]
                (next-ping exprand prev-ping))
              FIRSTPING
              exprands))

(defn ping-timestamp? [long-time]
  ((set (take-while #(> long-time %)
                    (map #(* 1000 %) pings-unix)))
   long-time))

(def pings-ms (map #(* 1000 %) pings-unix))

(defn is-ping? [long-time]
  (= long-time
     (last
      (take-while #(>= long-time %)
                  pings-ms))))

(def pings (map tc/from-long pings-ms))


(defn timestamp [line] (* 1000
                          (Integer. (first (str/split line #" ")))))

(defn tags [line]
  (set
   (next (str/split
          (if-let [i (str/index-of line "[")]
            (subs line 0 i)
            (subs line 0))
          #" "))))

(defn parse-log! [file]
  (into {}
        (map (fn [line]
               [(timestamp line) {:tags (tags line)}])
             (str/split (slurp
                         (io/file file))
                        #"\n"))))
