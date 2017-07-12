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

(defn deep-merge
  "Deeply merges maps so that nested maps are combined rather than replaced.
  For example:
  (deep-merge {:foo {:bar :baz}} {:foo {:fuzz :buzz}})
  ;;=> {:foo {:bar :baz, :fuzz :buzz}}
  ;; contrast with clojure.core/merge
  (merge {:foo {:bar :baz}} {:foo {:fuzz :buzz}})
  ;;=> {:foo {:fuzz :quzz}} ; note how last value for :foo wins"
  [& vs]
  (if (every? map? vs)
    (apply merge-with deep-merge vs)
    (last vs)))

(defn deep-merge-with
  "Deeply merges like `deep-merge`, but uses `f` to produce a value from the
  conflicting values for a key in multiple maps."
  [f & vs]
  (if (every? map? vs)
    (apply merge-with (partial deep-merge-with f) vs)
    (apply f vs)))
