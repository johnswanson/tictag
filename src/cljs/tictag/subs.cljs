(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [cljs-time.format :as f]
            [cljs-time.core :as t]
            [tictag.dates :refer [seconds-since-midnight days-since-epoch]]
            [tictag.utils :refer [descend]]
            [tictag.beeminder-matching :as beeminder-matching]
            [cljs.tools.reader.edn :as edn]
            [taoensso.timbre :refer-macros
             [trace debug info warn error fatal report
              tracef debugf infof warnf errorf fatalf reportf
              spy]]))


(def formatter (f/formatters :basic-date-time))

(reg-sub :ping-query (fn [db _] (:ping-query db)))

(reg-sub
 :query-fn
 (fn [_ _] (subscribe [:ping-query]))
 (fn [ping-query]
   (if ping-query
     (fn [{:keys [tags]}]
       (tags ping-query))
     (constantly false))))

(defn unnormalize [db thing & [other]]
  (if (not (vector? thing))
    (error "unnormalize: " thing))
  (if thing
    (get-in db thing other)
    other))

(reg-sub
 :raw-pings
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (filter #(= (:user %) user)
             (vals (:pings/by-timestamp db))))))

(reg-sub
 :ping-days
 (fn [_ _] (subscribe [:raw-pings]))
 (fn [pings _] (map :days-since-epoch pings)))

(reg-sub
 :max-ping-day
 (fn [_ _] (subscribe [:ping-days]))
 (fn [days _] (apply max days)))

(reg-sub
 :min-ping-day
 (fn [_ _] (subscribe [:ping-days]))
 (fn [days _] (apply min days)))

(reg-sub
 :sorted-pings
 (fn [_ _] (subscribe [:raw-pings]))
 (fn [pings _]
   (sort #(t/after? (:parsed-time %1) (:parsed-time %2)) pings)))

(reg-sub
 :pings
 (fn [_ _] [(subscribe [:sorted-pings])
            (subscribe [:query-fn])])
 (fn [[pings query-fn] _]
   (map #(assoc % :active? (query-fn %)) pings)))

(reg-sub
 :active-pings
 (fn [_ _] (subscribe [:pings]))
 (fn [pings _] (filter :active? pings)))

(reg-sub
 :window-size
 (fn [db _] (:db/window db)))

(reg-sub
 :matrix-plot-height
 (fn [_ _] (subscribe [:window-size]))
 (fn [{:keys [height]} _] (* 0.7 height)))

(reg-sub
 :matrix-plot-width
 (fn [_ _] (subscribe [:window-size]))
 (fn [{:keys [width]} _] (* 0.7 width)))

(reg-sub
 :count-meeting-query
 (fn [_ _]
   (subscribe [:active-pings]))
 (fn [pings _]
   (count pings)))

(reg-sub
 :day-totals
 (fn [_ _]
   (subscribe [:active-pings]))
 (fn [pings _]
   (->> pings
        (map :days-since-epoch)
        (frequencies))))

(reg-sub
 :minutes-for-tag
 (fn [[_ tag] _]
   (subscribe [:tag-count tag]))
 (fn [count _]
   (* count 45)))

(reg-sub
 :minutes-meeting-query
 (fn [_ _]
   (subscribe [:count-meeting-query]))
 (fn [count _]
   (* count 45)))

(reg-sub
 :minutes-per-day-for-tag
 (fn [[_ tag] _]
   [(subscribe [:minutes-for-tag tag])
    (subscribe [:total-time-in-days])])
 (fn [[minutes days] _]
   (/ minutes days)))

(reg-sub
 :minutes-per-day-for-tag-as-interval
 (fn [[_ tag] _]
   (subscribe [:minutes-per-day-for-tag tag]))
 (fn [minutes _]
   (t/minutes minutes)))

(reg-sub
 :time-per-day-for-tag
 (fn [[_ tag] _]
   (subscribe [:minutes-per-day-for-tag-as-interval tag]))
 (fn [interval _]
   (f/unparse-duration interval)))

(reg-sub
 :total-time
 (fn [_ _]
   (subscribe [:pings]))
 (fn [pings _]
   (* (count pings) 45)))

(reg-sub
 :total-time-in-days
 (fn [_ _]
   (subscribe [:pings]))
 (fn [pings _]
   (/ (* (count pings) 45) 60 24)))

(reg-sub
 :meeting-query-per-day
 (fn [_ _]
   [(subscribe [:minutes-meeting-query])
    (subscribe [:total-time-in-days])])
 (fn [[minutes days] _]
   (/ minutes days)))

(reg-sub
 :tag-counts
 (fn [_ _]
   (subscribe [:pings]))
 (fn [pings _]
   (->> pings
        (map :tags)
        (map frequencies)
        (apply merge-with +))))

(reg-sub
 :tag-count
 (fn [_ _]
   (subscribe [:tag-counts]))
 (fn [tag-counts [_ tag]]
   (get tag-counts tag 0)))

(reg-sub
 :total-ping-count
 (fn [_ _]
   (subscribe [:pings]))
 (fn [pings _]
   (count pings)))

(reg-sub
 :tag-%
 (fn [[_ tag] _]
   [(subscribe [:tag-count tag])
    (subscribe [:total-ping-count])])
 (fn [[tag-count total] _]
   (* 100 (/ tag-count total))))

(reg-sub
 :sorted-tag-counts
 (fn [_ _]
   (subscribe [:tag-counts]))
 (fn [tag-counts _]
   (keys
    (into (sorted-map-by (fn [key1 key2]
                           (compare [(get tag-counts key2) key2]
                                    [(get tag-counts key1) key1])))
          tag-counts))))

(reg-sub
 :authorized-user
 (fn [db _]
   (unnormalize db (:db/authenticated-user db))))

(reg-sub
 :beeminder
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (first
      (filter #(= (:user %) user)
              (vals (:beeminder/by-id db)))))))

(reg-sub
 :timezone
 (fn [_ _] (subscribe [:authorized-user]))
 (fn [user _]
   (:tz user)))


(defn valid-goal [{:keys [goal/tags] :as goal}]
  (assoc goal
         :goal/tags-valid?
         (beeminder-matching/valid?
          (try
            (edn/read-string tags)
            (catch js/Error _ nil)))))

(reg-sub
 :beeminder-goals
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (->> (:goal/by-id db)
          (vals)
          (filter #(and (= (:user %) user)
                        (not= (:goal/id %) :temp)))
          (map valid-goal)))))

(reg-sub
 :slack
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (first
      (filter #(= (:user %) user)
              (vals (:slack/by-id db)))))))

(reg-sub
 :goal/by-id
 (fn [db path]
   (when path (get-in db path))))

(reg-sub
 :active-panel
 (fn [db _]
   (some-> db :nav :handler)))

(reg-sub
 :login-errors
 (fn [db [_ field]]
   (some-> db :signup :errors field)))

(reg-sub
 :allowed-timezones
 (fn [db _]
   (map :name (:allowed-timezones db))))

