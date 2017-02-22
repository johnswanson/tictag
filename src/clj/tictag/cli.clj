(ns tictag.cli
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [tictag.google :as google]
            [tictag.beeminder :as beeminder]
            [tictag.db :as db]))

(defn update-ping
  [{:keys [db calendar beeminder]} {:as ping :keys [timestamp]}]
  (timbre/debugf "Updating ping: %s" (pr-str ping))
  (let [{:keys [id] :as result} (google/insert-event! calendar ping)]
    (timbre/debugf "Saved ping to google calendar, id: %s" id)
    (db/update-tags! db [(assoc ping :calendar-event-id id)]))
  (beeminder/sync! beeminder (db/get-pings (:db db))))

(defn update-pings
  [{:keys [db calendar beeminder]} pings]
  (timbre/debugf "Updating pings: %s" (pr-str pings))
  (doseq [{:as ping :keys [timestamp]} pings]
    (let [{:keys [id]} (google/insert-event! calendar ping)]
      (db/update-tags! db [(assoc ping :calendar-event-id id)])))
  (beeminder/sync! beeminder (db/get-pings (:db db))))

(defn sleep [component _]
  (let [pings (db/sleepy-pings (:db component))]
    (update-pings component (map #(assoc % :tags #{"sleep"}) pings))))

(defn tag-ping-by-long-time [component {:keys [long-time tags]}]
  (assert long-time)
  (update-ping component {:timestamp  long-time
                          :tags       tags}))

(defn tag-ping-by-id [component {:keys [id] :as args}]
  (let [long-time (db/pending-timestamp (:db component) id)]
    (tag-ping-by-long-time component (assoc args :long-time long-time))))

(defn tag-last-ping [component {:keys [tags]}]
  (let [[last-ping] (db/get-pings
                     (:db (:db component))
                     ["select * from pings order by ts desc limit 1"])]
    (update-ping component (assoc last-ping :tags tags))))


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

      (if (and (= cmd? "sleep") (= (count args) 0))
        {:command :sleep
         :args {}}
        {:command :tag-last-ping
         :args {:tags all-args}}))))

(defn handle-command [component body]
  (let [{:keys [command args]} (parse-body body)]
    (timbre/debugf "Received command body: %s, parsed as: %s %s" body command args)
    (case command
      :sleep                 (sleep component args)
      :tag-ping-by-id        (tag-ping-by-id component args)
      :tag-ping-by-long-time (tag-ping-by-long-time component args)
      :tag-last-ping         (tag-last-ping component args))))
