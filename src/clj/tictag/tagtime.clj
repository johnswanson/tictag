(ns tictag.tagtime
  "For parsing tagtime files"
  (:require [clj-time.coerce :as tc]
            [clojure.string :as str]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [honeysql.core :as sql]))

(defn parse-timestamp [ts]
  (tc/from-long (* (Integer. ts) 1000)))

(defn parse-tags [tags]
  (if (str/starts-with? tags "err [missed ping")
    ["ERROR-MISSED-PING"]
    (str/split tags #" ")))

(def formatter (f/formatter "yyyy.MM.dd HH:mm:ss"))

(defn parse-local-time [lt]
  (f/parse formatter (subs lt 0 19)))

(defn parse-line [line]
  (let [[_ ts tags local-time] (re-matches #"(\d+) (.+)\[(.+)\]" line)
        timestamp              (parse-timestamp ts)
        tags                   (parse-tags tags)
        local-time             (parse-local-time local-time)]
    {:ts        timestamp
     :tags      (str/join #" " tags)
     :tz_offset {:select [(sql/call :-
                                    (sql/call :cast local-time :timestamptz)
                                    (sql/call :cast timestamp :timestamptz))]}
     :user_id   1}))

(defn parse-lines [lines]
  (map parse-line (str/split lines #"\n")))

(defn parse [user log]
  (map
   #(assoc :user_id (:id user))
   (parse-lines log)))
