(ns tictag.cli
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre]))

(defn str-number? [s]
  (try (Long. s) (catch Exception e nil)))

(defn parse-body [body]
  (let [[cmd? & args :as all-args] (-> body
                                       (str/lower-case)
                                       (str/trim)
                                       (str/split #" "))]
    (if (str-number? cmd?)
      (if (> (count cmd?) 3)
        {:command :tag-ping-by-long-time
         :args {:tags args
                :long-time (Long. cmd?)}}
        {:command :tag-ping-by-id
         :args {:tags args
                :id cmd?}})

      (case cmd?
        "sleep" {:command :sleep :args {}}
        "\"" {:command :ditto :args {}}
        {:command :tag-last-ping
         :args {:tags all-args}}))))

