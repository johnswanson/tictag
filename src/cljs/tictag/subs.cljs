(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [cljs-time.format :as f]
            [tictag.dates :refer [seconds-since-midnight days-since-epoch]]))

(def formatter (f/formatters :date-time))
(defn parse-date [ping]
  (assoc ping
         :local-time (f/parse formatter (:local-time ping))
         :str-local-time (:local-time ping)))

(reg-sub :ping-query (fn [db _] (:ping-query db)))

(reg-sub
 :query-fn
 (fn [_ _] (subscribe [:ping-query]))
 (fn [ping-query]
   (if ping-query
     (fn [{:keys [tags]}]
       (tags (keyword ping-query)))
     (constantly false))))

(reg-sub
 :pings
 (fn [db _]
   (:pings db [])))

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
 (fn [pings _]
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
 (fn [_ _] (subscribe [:pings]))
 (fn [pings _]
   (map (comp seconds-since-midnight :local-time) pings)))

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
 :minutes-meeting-query
 (fn [_ _]
   (subscribe [:count-meeting-query]))
 (fn [count _]
   (* count 45)))

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
