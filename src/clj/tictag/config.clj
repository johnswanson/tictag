(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.string :as str]
            [buddy.core.codecs :as codecs]
            [buddy.core.keys :as keys]
            [buddy.core.hash :as hash]))

(def config
  {:tictag-server {:host (env :tictag-host "127.0.0.1")
                   :port (or (some-> env :tictag-port Integer.) 8080)}
   :db            {:dbtype   "postgresql"
                   :dbname   (env :pg-database)
                   :host     (env :pg-host "127.0.0.1")
                   :user     (env :pg-user)
                   :password (env :pg-password)}
   :crypto-key    (some-> env :tictag-crypto-key hash/sha256)
   :jwt           {:private-key (some-> env :ec-priv-key keys/str->private-key)
                   :public-key  (some-> env :ec-pub-key keys/str->public-key)}
   :slack         {:verification-token (env :slack-verification-token)}
   :tagtime       {:seed (or (some-> env :tagtime-seed Integer.) 666)
                   :gap  (or (some-> env :tagtime-gap Integer.) (* 60 45))}
   :run-scss?     (env :tictag-run-scss)
   :run-tests?    (env :tictag-run-tests)})

