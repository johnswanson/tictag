(ns tictag.client
  (:require [tictag.tagtime]
            [chime :refer [chime-ch]]
            [clojure.core.async :as a :refer [<! go-loop]]
            [com.stuartsierra.component :as component]
            [tictag.config :refer [config]]
            [org.httpkit.client :as http]
            [taoensso.timbre :as timbre]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clj-time.format :as f]
            [clj-time.core :as t]))

(defn play! [sound]
  (future (sh "/usr/bin/play" sound)))

(defn get-one-line [prompt tag-list]
  (play! "/usr/share/sounds/ubuntu/stereo/message-new-instant.ogg")
  (let [call (future (:out (sh "dmenu"
                               "-i"
                               "-b"
                               "-p" prompt
                               "-fn" "Ubuntu Mono-48"
                               "-nb" "#f00"
                               "-nf" "#0f0"
                               "-sb" "#00f"
                               "-sf" "#fff"
                               :in (str/join "\n" tag-list))))]
    (deref call (* 1000 60 5) "afk\n")))

(defn request-tags [prompt]
  (let [response-str (get-one-line prompt [])]
    (-> response-str
        (subs 0 (dec (count response-str)))
        (str/lower-case)
        (str/split #"[ ,]")
        (set))))

(defn send-tags-to-server [time tags]
  (let [{status :status :as response}
        @(http/request {:method :put
                        :timeout 3000
                        :url (str (:server-url config) "/time/" (tc/to-long time))
                        :headers {"Content-Type" "application/edn"}
                        :body (pr-str {:tags tags
                                       :local-time
                                       (f/unparse
                                        (f/formatters :date-hour-minute-second)
                                        (t/to-time-zone time (t/default-time-zone)))})})]
    (if (= status 200)
      (play! "/usr/share/sounds/ubuntu/stereo/message-new-instant.ogg")
      (do
        (play! "/usr/share/sounds/ubuntu/stereo/dialog-error.ogg")
        (timbre/errorf "Error response from server: %s" response)))))

(defrecord ClientChimer []
  component/Lifecycle
  (start [component]
    (timbre/debug "Beginning client chimer")
    (let [chimes (chime-ch
                  tictag.tagtime/pings
                  {:ch (a/chan (a/dropping-buffer 1))})]
      (go-loop []
        (when-let [time (<! chimes)]
          (let [tags (request-tags
                      (format "[%s] PING!"
                              (f/unparse
                               (f/formatters :date-hour-minute-second)
                               (t/to-time-zone time (t/default-time-zone)))))]
            (send-tags-to-server time tags))
          (recur)))
      (assoc component :stop #(a/close! chimes))))
  (stop [component]
    (timbre/debug "Stopping client chimer")
    (when-let [stop-fn (:stop component)]
      (stop-fn))
    (dissoc component :stop)))

(def system
  (component/system-map
   :client (->ClientChimer)))
