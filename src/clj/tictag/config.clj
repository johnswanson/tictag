(ns tictag.config
  (:require [environ.core :refer [env]]
            [clojure.string :as str]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]
            [buddy.core.keys :as keys]
            [buddy.core.hash :as hash]))

(def config
  {:tictag-server {:host                (env :tictag-host "127.0.0.1")
                   :port                (or (some-> env :tictag-port Integer.) 8080)
                   :slack-client-id     (env :slack-client-id)
                   :slack-client-secret (env :slack-client-secret)
                   :slack-verification-token (env :slack-verification-token)}
   :db            {:dbtype   "postgresql"
                   :dbname   (env :pg-database)
                   :host     (env :pg-host "127.0.0.1")
                   :user     (env :pg-user)
                   :password (env :pg-password)}
   :crypto-key    (some-> env :tictag-crypto-key hash/sha256)
   :jwt           {:private-key (some-> env :ec-priv-key b64/decode String. keys/str->private-key)
                   :public-key  (some-> env :ec-pub-key b64/decode String. keys/str->public-key)}
   :tagtime       {:seed (or (some-> env :tagtime-seed Integer.) 666)
                   :gap  (or (some-> env :tagtime-gap Integer.) (* 60 45))}
   :run-tests?    (env :tictag-run-tests)})

