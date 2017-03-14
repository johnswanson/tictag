(ns tictag.events
  (:require [re-frame.core :refer [reg-event-fx reg-cofx inject-cofx reg-fx]]
            [taoensso.timbre :as timbre]
            [tictag.slack :as slack]
            [tictag.db :as db]
            [tictag.beeminder :as beeminder]
            [tictag.cli :as cli]
            [tictag.google :as google]))

(defn report-changed-ping [old new]
  (format "Old ping: `%s`\nNew ping: `%s`"
          old new))

(defn receive-slack-callback
  "I received a call to /slack."
  [cofx [_ slack-message]]
  (let [valid? (:slack-validation-fn cofx)]
    (if (valid? slack-message)
      {:dispatch [:slack-message (get-in slack-message
                                         [:event :text])]}
      {})))

(defn sleepy-pings-cofx [db]
  (fn [coeffects]
    (assoc coeffects :sleepy-pings (db/sleepy-pings db))))

(defn slack-message [cofx [_ slack-message]]
  (let [{:keys [command args]} (cli/parse-body slack-message)]
    {:dispatch [command args]}))

(defn make-pings-sleepy [cofx _]
  {:pings (map #(assoc % :tags #{:sleep}) (:sleepy-pings cofx))
   :slack ["SLEEPING SOME MOFUCKIN PINGS"]})

(defn ping-by-id-cofx [db]
  (fn [cofx]
    (assoc cofx :ping-by-id #(db/pending-timestamp db %))))

(defn tag-ping-by-id [cofx [_ {:keys [tags id]}]]
  (let [old-ping ((:ping-by-id cofx) id)
        new-ping (assoc old-ping :tags tags)]
    {:pings [new-ping]
     :slack [(report-changed-ping old-ping new-ping)]}))

(defn tag-ping-by-long-time [cofx [_ {:keys [tags long-time]}]]
  {:pings [{:timestamp long-time :tags tags}]
   :slack [(report-changed-ping {} {:timestamp long-time :tags tags})]})

(defn tag-last-ping [cofx [_ {:keys [tags]}]]
  (let [old-ping (:last-ping cofx)
        new-ping (assoc old-ping :tags tags)]
    {:pings [new-ping]
     :slack [(report-changed-ping old-ping new-ping)]}))

(defn last-ping-cofx [db]
  (fn [cofx]
    (assoc cofx :last-ping (first (db/get-pings (:db db) ["select * from pings order by ts desc limit 1"])))))

(defn register!
  [{:keys [db beeminder tagtime twilio slack calendar shared-secret]}]

  (reg-cofx
   :timestamp-validation-fn
   (fn [coeffects]
     (assoc coeffects
            :timestamp-validation-fn
            #(and (= shared-secret (:secret %))
                  (db/is-ping? db (cli/str-number? (:timestamp %)))))))

  (reg-cofx
   :slack-validation-fn
   (fn [coeffects]
     (assoc coeffects
            :slack-validation-fn
            (partial slack/valid-event? slack))))

  (reg-cofx :sleepy-pings (sleepy-pings-cofx db))
  (reg-cofx :ping-by-id (ping-by-id-cofx db))
  (reg-cofx :last-ping (last-ping-cofx db))

  (reg-event-fx
   :receive-timestamp-req
   [(inject-cofx :timestamp-validation-fn)]
   (fn [coeffects [_ params]]
     (let [valid? (:timestamp-validation-fn coeffects)]
       (if (valid? params)
         (let [new-ping (-> params
                            (update :timestamp cli/str-number?)
                            (dissoc :secret))]
           {:pings [new-ping]
            :slack ["new ping: `%s`" new-ping]})
         {:slack ["invalid! `%s`"
                  (pr-str (dissoc params :secret))]}))))

  (reg-event-fx
   :receive-slack-callback
   [(inject-cofx :slack-validation-fn)]
   receive-slack-callback)

  (reg-event-fx :slack-message slack-message)

  (reg-event-fx
   :sleep
   [(inject-cofx :sleepy-pings)]
   make-pings-sleepy)

  (reg-event-fx
   :tag-ping-by-id
   [(inject-cofx :ping-by-id)]
   tag-ping-by-id)

  (reg-event-fx
   :tag-ping-by-long-time
   tag-ping-by-long-time)

  (reg-event-fx
   :tag-last-ping
   [(inject-cofx :last-ping)]
   tag-last-ping)

  (reg-fx
   :slack
   (fn [args]
     (slack/send-message! slack (apply format args))))

  (reg-fx
   :pings
   (fn [pings]
     (doseq [{:as ping :keys [timestamp]} pings]
       (let [{:keys [id]} (google/insert-event! calendar ping)]
         (db/update-tags! db [(assoc ping :calendar-event-id id)])))
     (beeminder/sync! beeminder (db/get-pings (:db db))))))

