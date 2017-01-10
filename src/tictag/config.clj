(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.java.io :as io]
            [clj-time.core :as t]))

(def config-map
  {:twilio-sid    {:doc "Twilio SID"}
   :twilio-token  {:doc "Twilio Token"}
   :twilio-number {:doc "The number to send pings from (your Twilio number)"}
   :text-number   {:doc "The number to send pings to (your cell number)"}

   :beeminder-auth-token {:doc "Your Beeminder API auth token"}
   :beeminder-user       {:doc "Your Beeminder user"}
   :beeminder-goals      {:doc "Your Beeminder goals, like {\"$GOAL_NAME\" (fn [{:keys [tags local-day local-time timestamp]}] (tags \"coding\"))}\n(This must be set in ~/.tictagrc, which will be read--unsafely!--using eval.)"}

   :server-host {:default "127.0.0.1"}
   :server-port {:default    8080
                 :conversion #(Integer. %)}

   :server-db-file {:doc     "Location of log file"
                    :default (str (System/getProperty "user.home") "/.tictag.log")}

   :server-url {:default "http://example.com"
                :doc     "Where to send tag data (where you're running the server)"}

   :local-time-zone {:default "America/Los_Angeles"}

   :tictag-shared-secret {:default "FIXMENOW"}

   :tagtime-seed {:default    666
                  :conversion #(Integer. %)}
   :tagtime-gap  {:default    (* 45 60)
                  :doc        "The number of seconds between pings on average, default: 30*60 (30 minutes)"
                  :conversion #(Integer. %)}})

(def default-config (into {}
                          (map (fn [[k v]]
                                 [k (:default v)])
                               config-map)))

(def config-file (format "%s/.tictagrc" (System/getProperty "user.home")))
(def config-edn #(eval (read-string (try (slurp (io/file config-file))
                                         (catch Exception e
                                           "{}")))))

(defn apply-conversions [m]
  (into {}
        (map (fn [[k v]]
               (if-let [conversion (:conversion (config-map k))]
                 [k (conversion v)]
                 [k v]))
             m)))

(def config
  (apply-conversions
   (merge
    default-config
    (select-keys (config-edn) (keys config-map))
    (select-keys env (keys config-map)))))

(def server
  {:ip   (:server-host config)
   :port (:server-port config)})

(def twilio
  {:account-sid   (:twilio-sid config)
   :account-token (:twilio-token config)
   :from          (:twilio-number config)})

