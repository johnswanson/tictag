(ns tictag.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [reagent.ratom]
            [ajax.core :as ajax]
            [clojure.set]
            [cljs-time.format :as f]
            [cljs-time.core :as t]
            [goog.crypt.base64 :as b64]
            [tictag.dates :refer [seconds-since-midnight days-since-epoch]]
            [tictag.utils :refer [descend]]
            [tictag.beeminder-matching :as beeminder-matching]
            [tictag.schemas :as schemas]
            [cljs.tools.reader.edn :as edn]
            [taoensso.timbre :as timbre :refer-macros
             [trace debug info warn error fatal report
              tracef debugf infof warnf errorf fatalf reportf
              spy]]
            [clojure.string :as str]))


(def formatter (f/formatters :basic-date-time))

(reg-sub :ping-query (fn [db _] (:ping-query db)))

(reg-sub
 :query-fn
 (fn [_ _] (subscribe [:ping-query]))
 (fn [ping-query]
   (if (seq ping-query)
     (let [q (try
               (edn/read-string ping-query)
               (catch js/Error _ ping-query))]
       (fn [{:keys [ping/tag-set]}]
         (beeminder-matching/match? q tag-set)))
     (constantly false))))


(reg-sub
 :raw-pings
 (fn [db _]
   (let [user (:db/authenticated-user db)]
     (vals (:ping/by-id db)))))

(reg-sub
 :ping-days
 (fn [_ _] (subscribe [:raw-pings]))
 (fn [pings _]
   (map :ping/days-since-epoch pings)))

(reg-sub
 :max-ping-day
 (fn [_ _] (subscribe [:sorted-pings]))
 (fn [pings _]
   (:ping/days-since-epoch (first pings))))

(reg-sub
 :min-ping-day
 (fn [_ _] (subscribe [:sorted-pings]))
 (fn [pings _] (:ping/days-since-epoch (last pings))))

(reg-sub
 :sorted-ping-ids
 (fn [db _]
   (:ping/sorted-ids db)))

(reg-sub
 :ping-map
 (fn [db _]
   (:ping/by-id db)))

(reg-sub
 :sorted-pings
 (fn [_ _]
   [(subscribe [:ping-map])
    (subscribe [:sorted-ping-ids])])
 (fn [[m ids] _]
   (for [id ids]
     (get m id))))

(reg-sub
 :ping-by-id
 (fn [db [_ id]]
   (let [pending-ping (get-in db [:tictag.schemas/ui :pending-ping/by-id id])
         saved-ping   (get-in db [:ping/by-id id])]
     (merge pending-ping saved-ping))))

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
 :count-meeting-query
 (fn [db _]
   (:ping-query-count db)))

(reg-sub
 :sorted-active-pings
 (fn [_ _] [(subscribe [:sorted-pings])
            (subscribe [:query-fn])])
 (fn [[pings query-fn] _]
  (map #(assoc % :active? (query-fn %)) pings)))

(defn daily-total [freqs]
  (fn [prev today]
    (let [[_ ytotal] (or (last prev) [0 0])]
      (conj prev [today (+ (* 0.75 (freqs today)) ytotal)]))))

(reg-sub
 :day-cum-totals
 (fn [_ _]
   (subscribe [:sorted-active-pings]))
 (fn [pings _]
   (let [first-day       (:ping/days-since-epoch (last pings))
         last-day        (:ping/days-since-epoch (first pings))
         freqs           (->> pings
                              (filter :active?)
                              (map :ping/days-since-epoch)
                              (frequencies))]
     (when (seq freqs)
       (reduce (daily-total freqs) [] (range first-day (inc last-day)))))))


(reg-sub
 :day-totals
 (fn [_ _]
   (subscribe [:active-pings]))
 (fn [pings _]
   (->> pings
        (map :ping/days-since-epoch)
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
 (fn [db _]
   (when-let [u (:db/authenticated-user db)]
     (/ (* (get-in db (conj u :user/ping-count))
           45)
        60
        24))))

(reg-sub
 :meeting-query-per-day
 (fn [_ _]
   [(subscribe [:minutes-meeting-query])
    (subscribe [:total-time-in-days])])
 (fn [[minutes days] _]
   (f/unparse-duration (t/minutes (/ minutes days)))))

(reg-sub
 :tag-counts
 (fn [db _]
   (:freq/by-tag db)))

(reg-sub
 :tag-count
 (fn [_ _]
   (subscribe [:tag-counts]))
 (fn [tag-counts [_ tag]]
   (or (get tag-counts tag)
       0)))

(reg-sub
 :tag-active?
 (fn [_ _]
   (subscribe [:ping-query]))
 (fn [p [_ tag]]
   (= p tag)))

(reg-sub
 :total-ping-count
 (fn [db _]
   (when-let [u (:db/authenticated-user db)]
     (get-in db (conj u :user/ping-count)))))

(reg-sub
 :query-%
 (fn [_ _]
   [(subscribe [:count-meeting-query])
    (subscribe [:total-ping-count])])
 (fn [[q-count total] _]
   (* 100 (/ q-count total))))

(reg-sub
 :tag-%
 (fn [[_ tag] _]
   [(subscribe [:tag-count tag])
    (subscribe [:total-ping-count])])
 (fn [[tag-count total] _]
   (* 100 (/ tag-count total))))

(reg-sub
 :sorted-tag-counts
 (fn [db _]
   (:freq/sorted-tags db)))

(reg-sub
 :authorized-user
 (fn [db _]
   (when-let [u (:db/authenticated-user db)]
     (get-in db u))))

(reg-sub
 :beeminder-id
 (fn [db _]
   (first (keys (:beeminder/by-id db)))))

(reg-sub
 :beeminder
 (fn [db [_ id]]
   (get-in db [:beeminder/by-id id])))

(reg-sub
 :beeminder-errors
 (fn [db [_ id]]
   (get-in db [:db/errors :beeminder/by-id id])))

(defn valid-goal [{:keys [goal/tags] :as goal}]
  (assoc goal
         :goal/tags-valid?
         (beeminder-matching/valid?
          (try
            (edn/read-string tags)
            (catch js/Error _ nil)))))

(reg-sub
 :slack-id
 (fn [db _]
   (first (keys (:slack/by-id db)))))

(reg-sub
 :slack
 (fn [db [_ id]]
   (let [pending-slack (get-in db [:tictag.schemas/ui :pending-slack/by-id id])
         saved-slack   (get-in db [:slack/by-id id])]
     (merge saved-slack pending-slack))))

(reg-sub
 :pending-user
 (fn [db _]
   (let [pending-user (get-in db [:tictag.schemas/ui :pending-user/by-id :temp])]
     (timbre/debug pending-user)
     (if (:pending-user/tz pending-user)
       pending-user
       (assoc pending-user :pending-user/tz "America/Los_Angeles")))))

(reg-sub
 :signup-errors
 (fn [db _]
   (let [errs (get-in db [:db/errors :user/by-id :temp])]
     errs)))

(reg-sub
 :login-errors
 (fn [db _]
   (get-in db [:db/errors :login])))

(reg-sub
 :slack-errors
 (fn [db [_ id]]
   (get-in db [:db/errors :slack/by-id id])))

(reg-sub
 :goal/by-id
 (fn [db path]
   (when path (get-in db path))))

(reg-sub
 :active-panel
 (fn [db _]
   (some-> db :nav :handler)))

(reg-sub
 :allowed-timezones
 (fn [db _]
   (map :name (:allowed-timezones db))))

(reg-sub
 :db/tagtime-uploads
 (fn [db _]
   (keys (:db/tagtime-upload db))))

(reg-sub
 :db/tagtime-upload
 (fn [db [_ k]]
   (get-in db [:db/tagtime-upload k])))

(reg-sub
 :macro
 (fn [db [_ id]]
   (let [pending-macro (get-in db [:tictag.schemas/ui :pending-macro/by-id id])
         saved-macro   (get-in db [:macro/by-id id])]
     (merge saved-macro pending-macro))))

(reg-sub
 :macros
 (fn [db]
   (sort (keys (:macro/by-id db)))))

(reg-sub
 :goal
 (fn [db [_ id]]
   (let [pending-goal (get-in db [:tictag.schemas/ui :pending-goal/by-id id])
         saved-goal (get-in db [:goal/by-id id])]
     (merge saved-goal pending-goal))))

(reg-sub
 :goals
 (fn [db]
   (sort (keys (:goal/by-id db)))))

(reg-sub
 :auth-token
 (fn [db _]
   (:auth-token db)))

(reg-sub
 :signed-query-url
 (fn [db _]
   (:ping-query-url db)))

(reg-sub
 :pie-filters/query
 (fn [db _] ))

(reg-sub
 :pie/result
 (fn [db _]
   (let [total (reduce + (vals (:pie/results db)))]
     (->> (:pie/results db)
          (sort #(compare (val %2) (val %1)))
          (map (fn [[k v]] {:name k :value v :start 0 :end v}))
          (reductions
           (fn [accu {:keys [name value]}]
             {:name name :start (:end accu 0) :end (+ (:end accu 0) value)}))
          (map (fn [v] (update v :start #(/ % total))))
          (map (fn [v] (update v :end #(/ % total))))))))

(reg-sub
 :pie/indexed-slices
 (fn [db _]
   (map-indexed #(zipmap [:id :query] %&) (:pie/slices db))))

(reg-sub
 :pie/pie-slices
 (fn [db _]
   (let [slices (conj (:pie/slices db) nil)
         result (:q-result db)]
     (for [q slices]
       [q (get result (edn/read-string q))]))))

(def days [:sun :mon :tue :wed :thu :fri :sat])

(def weekdays #{:mon :tue :wed :thu :fri})
(def weekends #{:sun :sat})

(reg-sub
 :pie-filters/days
 (fn [db _]
   (let [active-days (get-in db [:pie/filters :days])]
     (map
      (fn [day]
        {:selected? (get active-days day)
         :name      (str/capitalize (name day))
         :key       day})
      days))))

(reg-sub
 :pie-filters/day-set
 (fn [db _]
   (get-in db [:pie/filters :days])))

(reg-sub
 :pie-filters/all-days?
 (fn [_ _] (subscribe [:pie-filters/day-set]))
 (fn [days]
   (= days (clojure.set/union weekdays weekends))))

(reg-sub
 :pie-filters/no-days?
 (fn [_ _] (subscribe [:pie-filters/day-set]))
 (fn [days] (empty? days)))

(reg-sub
 :pie-filters/weekdays-only?
 (fn [_ _] (subscribe [:pie-filters/day-set]))
 (fn [days] (= days weekdays)))

(reg-sub
 :pie-filters/weekends-only?
 (fn [_ _] (subscribe [:pie-filters/day-set]))
 (fn [days] (= days weekends)))

(reg-sub
 :pie-filters/query
 (fn [db _]
   (get-in db [:pie/filters :query])))

(reg-sub
 :pie-filters/start-date
 (fn [db _]
   (when-let [t (get-in db [:pie/filters :start-date])]
     t)))

(reg-sub
 :pie-filters/end-date
 (fn [db _]
   (when-let [t (get-in db [:pie/filters :end-date])]
     t)))

