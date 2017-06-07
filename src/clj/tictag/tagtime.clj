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
  (filter seq
          (-> tags
              (str/replace #"\([^\(\)]*\)" "")
              (str/replace #"\[[^\[\]]*\]" "")
              (str/split #" "))))

(def formatter (f/formatter "yyyy.MM.dd HH:mm:ss E"))

(defn parse-local-time [time-str]
  (when (= (count time-str) 23)
    (f/parse formatter time-str)))


(defn sanity-check [ts tags]
  (and ts tags))

(defn offset [{timestamp :ts local-time :local-time}]
  (t/seconds
   (if (t/after? timestamp local-time)
     (- (t/in-seconds (t/interval local-time timestamp)))
     (t/in-seconds (t/interval timestamp local-time)))))

(defn parse-line [line]
  (let [result (when-let [regex-matched? (re-matches #"(\d+) (.+)\[(.+)\]$" line)]
                 (let [[_ ts tags local-time] regex-matched?
                       timestamp              (parse-timestamp ts)
                       tags                   (parse-tags tags)
                       local-time             (parse-local-time local-time)]
                   (when (sanity-check timestamp tags)
                     {:ts        timestamp
                      :tags      (str/join #" " tags)
                      :local-time local-time})))]
    (if result result (tracef "Invalid tagtime line: %s" line))))

(defn add-tz-offset [{:keys [local-time ts] :as m}]
  (assoc m
         :tz-offset
         {:select [(sql/call :-
                             (sql/call :cast local-time :timestamptz)
                             (sql/call :cast ts :timestamptz))]}))

(defn parse [user-id log]
  (->> (str/split log #"\n")
       (map parse-line)
       (filter #(not (nil? %)))
       (map #(assoc % :user-id user-id))
       (reduce (fn [accu {:keys [local-time ts] :as m}]
                 (if local-time
                   (conj accu m)
                   (if-let [{prev :local-time} (peek accu)]
                     (conj accu (assoc m :local-time (t/plus ts (offset prev))))
                     accu)))
               [])
       (map add-tz-offset)
       (map #(select-keys % [:tz-offset :ts :tags]))
       seq))
