(ns tictag.config
  (:require [environ.core :refer [env]]))

(def home-dir (System/getProperty "user.home"))

(def beeminder-goal-file (env :beeminder-goal-file (format "%s/.tictag.goals" home-dir)))

(def goals
  (try (load-file beeminder-goal-file)
       (catch Exception _ {})))

(def config
  {:tictag-server {:shared-secret (env :tictag-shared-secret)
                   :host          (env :tictag-host "127.0.0.1")
                   :port          (or (some-> env :tictag-port Integer.) 8080)}
   :db            {:file (env :db-file (format "%s/.tictag.log" home-dir))}
   :twilio        {:account-sid   (env :twilio-account-sid)
                   :account-token (env :twilio-account-token)
                   :from          (env :twilio-from)
                   :to            (env :twilio-to)}
   :beeminder     {:auth-token (env :beeminder-auth-token)
                   :user       (env :beeminder-user)
                   :goals      goals}
   :tagtime       {:seed (or (some-> env :tagtime-seed Integer.) 666)
                   :gap  (or (some-> env :tagtime-gap Integer.) (* 60 45))}})

(def beeminder (:beeminder config))
(def tagtime (:tagtime config))
