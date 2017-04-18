(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.string :as str]
            [buddy.core.codecs :as codecs]
            [buddy.core.keys :as keys]))

(def config
  {:tictag-server {:host (env :tictag-host "127.0.0.1")
                   :port (or (some-> env :tictag-port Integer.) 8080)}
   :db            {:dbtype   "postgresql"
                   :dbname   (env :pg-database)
                   :host     (env :pg-host "127.0.0.1")
                   :user     (env :pg-user)
                   :password (env :pg-password)}
   :crypto-key    (codecs/hex->bytes (or (:tictag-crypto-key env) "deadbeef"))
   :jwt           {:private-key (keys/str->private-key (env :ec-priv-key))
                   :public-key  (keys/str->public-key (env :ec-pub-key))}
   :slack         {:verification-token (env :slack-verification-token)}
   :tagtime       {:seed (or (some-> env :tagtime-seed Integer.) 666)
                   :gap  (or (some-> env :tagtime-gap Integer.) (* 60 45))}
   :run-scss?     (env :tictag-run-scss)
   :run-tests?    (env :tictag-run-tests)})

