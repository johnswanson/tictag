(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def config-map
  {:twilio-sid {:doc "Twilio SID"}
   :twilio-token {:doc "Twilio Token"}
   :twilio-number {:doc "The number to send pings from (your Twilio number)"}
   :text-number {:doc "The number to send pings to (your cell number)"}

   :beeminder-auth-token {:doc "Your Beeminder API auth token"}

   :server-host {:default "127.0.0.1"}
   :server-port {:default "8080"
                 :conversion #(Integer. %)}

   :server-db-file {:doc "Location of log file"
                    :default (str (System/getProperty "user.home") "/.tictag.clj")}

   :server-url {:default "http://example.com"
                :doc "Where to send tag data (where you're running the server)"}

   :tagtime-seed {:default "666"
                  :conversion #(Integer. %)}
   :tagtime-gap {:default (str (* 45 60))
                 :doc "The number of seconds between pings on average, default: 30*60 (30 minutes)"
                 :conversion #(Integer. %)}})

(def default-config (into {}
                          (map (fn [[k v]]
                                 [k (:default v)])
                               config-map)))

(def config-file (format "%s/.tictagrc" (System/getProperty "user.home")))
(def config-edn #(edn/read-string (try (slurp (io/file config-file))
                                       (catch Exception e
                                         nil))))

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

