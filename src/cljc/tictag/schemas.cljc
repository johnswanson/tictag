(ns tictag.schemas
  (:require #?(:clj [clj-time.core :as t]
               :cljs [cljs-time.core :as t])
            [struct.core :as st]))

(def timezone
  {:message  "must be a time zone, e.g. America/Los_Angeles"
   :optional true
   :state    true
   :validate (fn [_ v timezones?]
               (timezones? v))})

(defn +new-user-schema+ [tzs]
  {:username [st/required st/string]
   :password [st/required st/string]
   :email    [st/required st/string]
   :tz       [st/required st/string [timezone tzs]]})

(def validate st/validate)
