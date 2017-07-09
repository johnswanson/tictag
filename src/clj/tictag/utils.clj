(ns tictag.utils
  (:require [com.stuartsierra.component :as component]
            [clj-time.local]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

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

