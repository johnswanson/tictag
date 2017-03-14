(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.string :as str]))

;; goal format
;; "work,personal:testing" => 2 goals
;; tagged 'work'? goal is [user]/work
;; tagged 'personal'? goal is [user]/testing

(defn get-goals [goal-str]
  (when goal-str
    (into
     {}
     (for [goal (str/split goal-str #",")]
       (let [[tag beeminder-goal] (str/split goal #":")]
         (if beeminder-goal
           [(keyword tag) beeminder-goal]
           [(keyword tag) tag]))))))

(def config
  {:tictag-server {:host (env :tictag-host "127.0.0.1")
                   :port (or (some-> env :tictag-port Integer.) 8080)}
   :db            {:dbtype   "postgresql"
                   :dbname   (env :pg-database)
                   :host     (env :pg-host "127.0.0.1")
                   :user     (env :pg-user)
                   :password (env :pg-password)}
   :twilio        {:account-sid   (env :twilio-account-sid)
                   :account-token (env :twilio-account-token)
                   :from          (env :twilio-from)
                   :to            (env :twilio-to)
                   :disable?      (env :twilio-disable)}
   :beeminder     {:auth-token (env :beeminder-auth-token)
                   :user       (env :beeminder-user)
                   :goals      (get-goals (env :beeminder-goals))
                   :disable?   (env :beeminder-disable)}
   :google        {:client-id     (env :google-client-id)
                   :client-secret (env :google-client-secret)
                   :refresh-token (env :google-refresh-token)
                   :calendar-id   (env :google-calendar-id)
                   :disable?      (env :google-disable)}
   :slack         {:token              (env :slack-token)
                   :username           (env :slack-username)
                   :verification-token (env :slack-verification-token)}
   :shared-secret (env :tictag-shared-secret)
   :tagtime       {:seed (or (some-> env :tagtime-seed Integer.) 666)
                   :gap  (or (some-> env :tagtime-gap Integer.) (* 60 45))}

   :run-scss?  (env :tictag-run-scss)
   :run-tests? (env :tictag-run-tests)})


(def beeminder (:beeminder config))
(def tagtime (:tagtime config))
