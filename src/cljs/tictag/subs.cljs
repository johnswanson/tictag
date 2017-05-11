(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [cljs-time.format :as f]
            [cljs-time.core :as t]
            [tictag.dates :refer [seconds-since-midnight days-since-epoch]]
            [tictag.utils :refer [descend]]))

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
    (js/console.error "unnormalize: " thing))
  (if thing
    (get-in db thing other)
    other))

(reg-sub
 :pings
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (js/console.log user (:pings/by-timestamp db))
     (filter #(= (:user %) user)
             (vals (:pings/by-timestamp db))))))

(reg-sub
 :ping-active?
 (fn [_ _]
   (subscribe [:query-fn]))
 (fn [query-fn [_ ping]]
   (query-fn ping)))

(reg-sub
 :parsed-times
 (fn [_ _]
   (subscribe [:pings]))
 (fn parse-times [pings _]
   (map #(f/parse formatter (:local-time %)) pings)))

(reg-sub
 :days-since-epoch
 (fn [_ _]
   (subscribe [:parsed-times]))
 (fn [parsed-times _]
   (map days-since-epoch parsed-times)))

(reg-sub
 :min-days-since-epoch
 (fn [_ _]
   (subscribe [:days-since-epoch]))
 (fn [days _]
   (apply min days)))

(reg-sub
 :max-days-since-epoch
 (fn [_ _] (subscribe [:days-since-epoch]))
 (fn [days _]
   (apply max days)))

(reg-sub
 :seconds-since-midnight
 (fn [_ _] (subscribe [:parsed-times]))
 (fn [parsed-times _]
   (map seconds-since-midnight parsed-times)))

(reg-sub
 :matrix-plot-height
 (constantly 500))
(reg-sub
 :matrix-plot-width
 (constantly 500))

(reg-sub
 :matrix-plot-domain-x
 (fn [_ _]
   [(subscribe [:min-days-since-epoch])
    (subscribe [:max-days-since-epoch])])
 (fn [[min max] _]
   [min max]))

(reg-sub
 :matrix-plot-domain-y
 (constantly [0 (* 24 60 60)]))

(reg-sub
 :matrix-plot-range-x
 (fn [_ _] (subscribe [:matrix-plot-width]))
 (fn [width _] [0 (- width 10)]))

(reg-sub
 :matrix-plot-range-y
 (fn [_ _] (subscribe [:matrix-plot-height]))
 (fn [height _] [50 (- height 10)]))

(defn pixel [domain range x]
  (let [max-domain  (apply max domain)
        min-domain  (apply min domain)
        domain-diff (- max-domain min-domain)

        max-range  (apply max range)
        min-range  (apply min range)
        range-diff (- max-range min-range)]
    (+ min-range (* range-diff (/ (- x min-domain) domain-diff)))))

(reg-sub
 :ping-pixel
 (fn [_ _]
   [(subscribe [:matrix-plot-range-x])
    (subscribe [:matrix-plot-range-y])
    (subscribe [:matrix-plot-domain-x])
    (subscribe [:matrix-plot-domain-y])])
 (fn [[range-x range-y
       domain-x domain-y] [_ {:keys [x y]}]]
   {:x (pixel domain-x range-x x)
    :y (pixel domain-y range-y y)}))

(reg-sub
 :matrix-plot-pings
 (fn [_ _]
   [(subscribe [:pings])
    (subscribe [:seconds-since-midnight])
    (subscribe [:days-since-epoch])])
 (fn [[pings seconds days] _]
   (map (fn [d s p]
          (assoc p :x d :y s))
        days seconds pings)))

(reg-sub
 :count-meeting-query
 (fn [_ _]
   [(subscribe [:query-fn])
    (subscribe [:pings])])
 (fn [[query-fn pings] _]
   (count (filter query-fn pings))))

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
 :beeminder-goals
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (filter #(and (= (:user %) user)
                   (not= (:goal/id %) :temp))
             (vals (:goal/by-id db))))))

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

