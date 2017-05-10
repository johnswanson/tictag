(ns tictag.utils
  (:require [taoensso.timbre :as timbre]))

(defn get-thing [db cursor thing]
  (let [source (if (vector? cursor)
                 (get-in db cursor)
                 cursor)]
    (get source thing)))

(defn descend [db path]
  (loop [cursor db
         path   path]
    (if (seq path)
      (recur
       (get-thing db cursor (first path))
       (rest path))
      (if (vector? cursor)
        (get-in db cursor)
        cursor))))

