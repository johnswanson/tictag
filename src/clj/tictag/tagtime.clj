(ns tictag.tagtime
  "For parsing tagtime files"
  (:require [clj-time.coerce :as tc]
            [clojure.string :as str]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [honeysql.core :as sql]
            [taoensso.timbre :refer [refer-timbre]]))
(refer-timbre)

(defn parse-timestamp [ts]
  (try (tc/from-long (* (Integer. ts) 1000))
       (catch Exception _ nil)))

(defn parse-tags [tags]
  (if (str/starts-with? tags "err [missed ping")
    ["ERROR-MISSED-PING"]
    (str/split tags #" ")))

(def formatter (f/formatter "yyyy.MM.dd HH:mm:ss"))

(defn parse-local-time [lt]
  (f/parse formatter (subs lt 0 19)))

(defn sanity-check [ts tags local-time]
  (and ts tags local-time))

(defn parse-line [line]
  (let [result (when-let [regex-matched? (re-matches #"(\d+) (.+)\[(.+)\]" line)]
                 (let [[_ ts tags local-time] regex-matched?
                       timestamp              (parse-timestamp ts)
                       tags                   (parse-tags tags)
                       local-time             (parse-local-time local-time)]
                   (when (sanity-check timestamp tags local-time)
                     {:ts        timestamp
                      :tags      (str/join #" " tags)
                      :tz_offset {:select [(sql/call :-
                                                     (sql/call :cast local-time :timestamptz)
                                                     (sql/call :cast timestamp :timestamptz))]}})))]
    (if result result (tracef "Invalid tagtime line: %s" line))))

(defn parse [user-id log]
  (->> (str/split log #"\n")
       (map parse-line)
       (filter #(not (nil? %)))
       (map #(assoc % :user-id user-id))
       seq))
