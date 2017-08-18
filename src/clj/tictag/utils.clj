(ns tictag.utils
  (:require [com.stuartsierra.component :as component]
            [clj-time.local]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]))

(def wtf (f/formatter "yyyy-MM-dd HH:mm:ss"))

(defn local-time [time]
  (clj-time.local/format-local-time
   (t/to-time-zone time (t/default-time-zone))
   :date-time))

(defn local-time-from-long [long-time]
  (local-time (tc/from-long long-time)))

(defn system-map [m]
  (apply component/system-map
         (flatten (into [] m))))

(defn str-number? [s]
  (try (Long. s) (catch Exception e nil)))

(defn success? [?http-resp]
  (let [status (:status ?http-resp)]
    (and status (<= 200 status 299))))

(defn kebab-str [kw]
  (str/replace (name kw) #"_" "-"))

(defn with-ns [m ns]
  (when m
    (into {}
          (map
           (fn [[k v]]
             [(keyword ns (kebab-str k)) v])
           m))))

(defn without-ns [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v]) m)))

(defn to-entities [db]
  (->> db
       (map
        (fn [[k m]]
          (map (fn [[id entity]]
                 {:path      [k id]
                  :selector  k
                  :namespace (namespace k)
                  :type      (keyword (namespace k))
                  :id        id
                  :entity    entity})
               m)))
       (flatten)))

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

(defn deep-merge*
  [v1 v2]
  (if (nil? v2) v1 (deep-merge v1 v2)))

(def ascending compare)
(def descending #(compare %2 %1))

(defn compare-by [& key-cmp-pairs]
  (fn [x y]
    (loop [[k cmp & more] key-cmp-pairs]
      {:pre [(keyword? k), (fn? cmp), (even? (count more))]}
      (let [result (cmp (k x) (k y))]
        (if (and (zero? result) more)
          (recur more)
          result)))))

(defn ignore-trailing-slash
  "Modifies the request uri before calling the handler.
  Removes a single trailing slash from the end of the uri if present.

  Useful for handling optional trailing slashes until Compojure's route matching syntax supports regex.
  Adapted from http://stackoverflow.com/questions/8380468/compojure-regex-for-matching-a-trailing-slash"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request :uri (if (and (not (= "/" uri))
                                            (.endsWith uri "/"))
                                     (subs uri 0 (dec (count uri)))
                                     uri))))))

