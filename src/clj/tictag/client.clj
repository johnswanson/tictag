(ns tictag.client
  (:require [tictag.tagtime]
            [chime :refer [chime-ch]]
            [clojure.core.async :as a :refer [<! go-loop]]
            [com.stuartsierra.component :as component]
            [org.httpkit.client :as http]
            [taoensso.timbre :as timbre]
            [me.raynes.conch :refer [with-programs]]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clj-time.local]
            [tictag.tagtime :as tagtime]
            [tictag.client-config]
            [tictag.utils :as utils]
            [clojure.edn :as edn]))

(with-programs [play]
  (defn play! [sound]
    (play sound)))


(defn get-one-line [prompt tag-list]
  (play! "/usr/share/sounds/ubuntu/stereo/message-new-instant.ogg")
  (with-programs [dmenu]
    (try
      (dmenu "-i"
             "-b"
             "-p" prompt
             "-fn" "Ubuntu Mono-48"
             "-nb" "#f00"
             "-nf" "#0f0"
             "-sb" "#00f"
             "-sf" "#fff"
             {:in (str/join "\n" tag-list)
              :timeout (* 60 1000)})
      (catch Exception e
        nil))))

(defn request-tags [prompt]
  (let [response-str (get-one-line prompt [])]
    (when (seq response-str)
      (-> response-str
          (subs 0 (dec (count response-str)))
          (str/lower-case)
          (str/split #"[ ,]")
          (set)))))

(defn send-tags-to-server [server-url secret time tags]
  (let [{status :status :as response}
        @(http/request {:method :put
                        :timeout 3000
                        :url (str server-url "/time/" (tc/to-long time))
                        :headers {"Content-Type" "application/edn"}
                        :body (pr-str {:tags tags
                                       :secret secret
                                       :local-time
                                       (utils/local-time time)})})]
    (if (= status 200)
      (play! "/usr/share/sounds/ubuntu/stereo/message-new-instant.ogg")
      (do
        (play! "/usr/share/sounds/ubuntu/stereo/dialog-error.ogg")
        (timbre/errorf "Error response from server: %s" response)))))

(defrecord ClientChimer [server-url]
  component/Lifecycle
  (start [component]
    (timbre/debug "Beginning client chimer")
    (timbre/debugf "Fetching config from remote [%s]..." server-url)
    (let [shared-secret (tictag.client-config/shared-secret)
          {:keys [tagtime-seed tagtime-gap]} (-> (format "%s/config" server-url)
                                                 (http/get {:as :text})
                                                 deref
                                                 :body
                                                 edn/read-string)
          _ (timbre/debugf "Received configuration from remote. Gap: %s, seed: %s" tagtime-gap tagtime-seed)
          tagtime (tagtime/tagtime tagtime-gap tagtime-seed)
          chimes (chime-ch
                  (:pings tagtime)
                  {:ch (a/chan (a/dropping-buffer 1))})]
      (go-loop []
        (when-let [time (<! chimes)]
          (timbre/debug "Pinging client")
          (when-let [tags (request-tags
                           (format "[%s] PING!"
                                   (utils/local-time time)))]
            (send-tags-to-server server-url shared-secret time tags))
          (recur)))
      (assoc component :stop #(a/close! chimes))))
  (stop [component]
    (timbre/debug "Stopping client chimer")
    (when-let [stop-fn (:stop component)]
      (stop-fn))
    (dissoc component :stop)))

(defn system [server-url]
  {:client (->ClientChimer server-url)})
