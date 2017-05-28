(ns tictag.beeminder-matching
  (:require [taoensso.timbre :as timbre]))

(defn valid? [thing]
  (cond
    (coll? thing) (let [[c & args] thing]
                    (and (#{'not 'and 'or :not :and :or} c)
                         (every? valid? args)))
    (keyword? thing) true
    (string? thing) true))

(defn match? [thing b]
  (cond
    (coll? thing)    (let [[pred & args] thing]
                       (case pred
                         (and :and) (every? #(match? % b) args)
                         (or :or)   (some #(match? % b) args)
                         (not :not) (not (some #(match? % b) args))
                         false))
    (keyword? thing) (b (name thing))
    (symbol? thing)  (b (name thing))
    (string? thing)  (b thing)))
