(ns tictag.filters
  (:require [tictag.beeminder-matching :as bm]
            [clojure.set]
            [taoensso.timbre :as timbre]
            [tictag.db :as db]
            [clj-time.predicates]
            [clj-time.core :as t]
            [clojure.string]
            [clj-time.format :as f]
            [clojure.edn :as edn]))

(defn match-query? [ping q]
  (bm/match? q (:ping/tag-set ping)))

(defn match-start-date? [ping start-date]
  (t/before? (f/parse (f/formatter "YYYY-MM-dd") start-date) (:ping/local-time ping)))

(defn match-end-date? [ping end-date]
  (t/after? (f/parse (f/formatter "YYYY-MM-dd") end-date) (:ping/local-time ping)))

(def days-of-week [:mon :tue :wed :thu :fri :sat :sun])

(defn day-of-week [p]
  (get days-of-week (dec (t/day-of-week (:ping/local-time p)))))

(defn match-days? [ping days]
  (let [d (day-of-week ping)]
    (get days d)))

(defn match? [ping [ftype v]]
  (try
    (if-not v
      true
      (case ftype
        :query (match-query? ping (edn/read-string v))
        :start-date (match-start-date? ping v)
        :end-date (match-end-date? ping v)
        :days (match-days? ping v)))
    (catch Exception e
      true)))

(defn matching [pings filters]
  (filter (fn [p]
            (every? #(match? p %) filters))
          pings))

(defn sieve [pings {filters :filters slices :slices}]
  (let [pings          (matching pings filters)
        filtered-pings (->> slices
                            (reduce
                             (fn [accu f]
                               (let [{:keys [yes no]} (group-by #(if (bm/match? f (:ping/tag-set %)) :yes :no) (get accu nil))]
                                 (-> accu
                                     (assoc f yes)
                                     (assoc nil no))))
                             {nil pings}))]
    {:pie/shares    (->> filtered-pings
                         (map (fn [[k v]] [k (count v)]))
                         (into {}))
     :pie/others    (->> (filtered-pings nil)
                         (map :ping/tag-set)
                         (apply concat)
                         (frequencies)
                         (sort-by val #(compare %2 %1)))
     :pie/histogram (into {} (for [s slices]
                               [s (->>
                                   (filter #(bm/match? s (:ping/tag-set %)) pings)
                                   (map :ping/days-since-epoch)
                                   (frequencies))]))}))
