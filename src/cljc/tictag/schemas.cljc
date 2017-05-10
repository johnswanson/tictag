(ns tictag.schemas
  (:require #?(:clj [clj-time.core :as t]
               :cljs [cljs-time.core :as t])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec :as s])
            #?(:clj [clojure.edn :refer [read-string]]
               :cljs [cljs.reader :refer [read-string]])
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

(def rule
  {:message "must be a valid rule, e.g. [:and :this :that]"
   :optional true
   :state false
   :validate identity})

(def +goal-schema+
  {:goal [st/required st/string]
   :tags [st/required rule]
   :id   [st/integer]})

(defn valid-edn? [s]
  (try (read-string s)
       (catch #?(:clj Exception :cljs js/Error) _ nil)))

(s/def :goal/name string?)
(s/def :goal/tags (s/and string? valid-edn?))
(s/def :goal/id (s/or :goal/id integer? :goal/id #{:temp}))
(s/def ::goal (s/keys :req [:goal/name :goal/tags :goal/id]))

(s/def :beeminder/goal (s/keys :req [:goal/name :goal/tags :goal/id]))
(s/def :beeminder/goals (s/coll-of :beeminder/goal))

(s/def :beeminder/user-id integer?)
(s/def :beeminder/username string?)
(s/def :beeminder/token string?)
(s/def :beeminder/enabled? boolean?)

(s/def :user/beeminder (s/keys :req [:beeminder/user-id :beeminder/username :beeminder/token :beeminder/enabled? :beeminder/goals]))

(s/def :user/slack (s/keys :req [:slack/user-id :slack/username]))

(s/def :db/user (s/keys :opt [:user/beeminder :user/slack] :req [:user/username :user/timezone :user/email]))

(s/def ::db
  (s/keys :req
          [:user/by-id
           :goal/by-id
           :ping/by-timestamp
           :beeminder-token/by-id
           :slack/by-id
           :db/authenticated-user]))

