(ns tictag.schemas
  (:require #?(:clj [clj-time.core :as t]
               :cljs [cljs-time.core :as t])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec :as s])
            #?(:clj [clojure.edn :refer [read-string]]
               :cljs [cljs.reader :refer [read-string]])
            [struct.core :as st]
            [tictag.beeminder-matching :as bm]))

(defn valid-bm-edn? [s]
  (try (bm/valid? (read-string s))
       (catch #?(:clj Exception :cljs js/Error) _ nil)))

(s/def :goal/name string?)
(s/def :goal/tags (s/and string? valid-bm-edn?))
(s/def :goal/id (s/or :goal/id integer? :goal/id #{:temp}))
(s/def ::goal (s/keys :req [:goal/name :goal/tags :goal/id]))

(s/def :macro/id integer?)
(s/def :macro/expands-from string?)
(s/def :macro/expands-to string?)
(s/def :macro/user-id integer?)

(s/def ::pg-macro
  (s/keys :req-un [:macro/id :macro/expands_from :macro/expands_to :macro/user_id]))

(s/def ::macro
  (s/keys :req [:macro/id :macro/expands-from :macro/expands-to :macro/user-id]))

(s/def :macro/by-id
  (s/map-of :macro/id ::macro))

(s/def :goal/by-id
  (s/map-of :goal/id ::goal))

(s/def ::db
  (s/keys :opt [:macro/by-id]))

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

(def rule
  {:message "must be a valid rule, e.g. [:and :this :that]"
   :optional true
   :state false
   :validate identity})

