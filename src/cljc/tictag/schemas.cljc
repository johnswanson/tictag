(ns tictag.schemas
  (:require #?(:clj [clj-time.core :as t]
               :cljs [cljs-time.core :as t])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec :as s])
            #?(:clj [clojure.edn :refer [read-string]]
               :cljs [cljs.reader :refer [read-string]])
            [struct.core :as st]
            [tictag.beeminder-matching :as bm]
            [taoensso.timbre :as timbre]))

(defn valid-bm-edn? [s]
  (try (bm/valid? (read-string s))
       (catch #?(:clj Exception :cljs js/Error) _ nil)))

(s/def :pending-macro/id (s/or :id integer? :tempid #{:temp}))
(s/def :pending-macro/expands-from string?)
(s/def :pending-macro/expands-to string?)
(s/def :pending-macro/error keyword?)
(s/def ::pending-macro
  (s/keys :opt [:pending-macro/id
                :pending-macro/tempid
                :pending-macro/expands-from
                :pending-macro/expands-to
                :pending-macro/error]))

(s/def :goal/goal string?)
(s/def :goal/tags (s/and string? valid-bm-edn?))
(s/def :goal/id (s/or :goal/id integer? :goal/id #{:temp}))
(s/def ::goal (s/keys :req [:goal/goal :goal/tags :goal/id]))

(s/def :pending-goal/id (s/or :id integer? :tempid #{:temp}))
(s/def :pending-goal/goal :goal/goal)
(s/def :pending-goal/tags :goal/tags)

(s/def ::pending-goal
  (s/keys :opt [:pending-goal/id
                :pending-goal/goal
                :pending-goal/tags]))

(s/def :macro/id integer?)
(s/def :macro/expands-from string?)
(s/def :macro/expands-to string?)
(s/def :macro/user-id integer?)

(s/def :pending-macro/by-id
  (s/map-of :pending-macro/id ::pending-macro))

(s/def :pending-goal/by-id
  (s/map-of :pending-goal/id ::pending-goal))

(s/def ::ui
  (s/keys :opt [:pending-macro/by-id
                :pending-goal/by-id]))

(s/def ::macro
  (s/keys :req [:macro/id :macro/expands-from :macro/expands-to :macro/user-id]))

(s/def :macro/by-id
  (s/map-of :macro/id ::macro))

(s/def :goal/by-id
  (s/map-of :goal/id ::goal))

(s/def ::db
  (s/keys :opt [:macro/by-id
                :goal/by-id
                ::ui]))

(defn valid-db? [db]
  (s/valid? ::db db))

(defn assert-valid-db! [db]
  (when-not (valid-db? db)
    (timbre/error (valid-db? db))
    (timbre/error (s/explain-data ::db db))))


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

