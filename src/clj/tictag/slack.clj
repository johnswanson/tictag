(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [clojure.string :as str]
            [oauth.v2 :as v2]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

(def oauth-access-token-url "https://slack.com/api/oauth.access")
(def oauth-authorization-url "https://slack.com/oauth/authorize")
(def oauth-authorization-defaults
  {:scope "bot"})
(defn oauth-authorization-url
  [client-id redirect-uri & {:as options}]
  (apply v2/oauth-authorization-url oauth-authorization-url client-id redirect-uri
         (mapcat concat (merge oauth-authorization-defaults options))))
(defn oauth-access-token
  [client-id client-secret code redirect-uri]
  (v2/oauth-access-token oauth-access-token-url client-id client-secret code redirect-uri))

(def slack-api-url "https://slack.com/api/")
(defn method-url [m] (str slack-api-url m))
(defn slack-call! [cmd params]
  (-> (method-url cmd)
      (http/post {:form-params params})
      deref
      :body
      (json/parse-string true)))

(defn im-open [token user-id]
  (slack-call! "im.open" {:token token :user user-id}))

(defn users-info [token user-id]
  (slack-call! "users.info" {:token token :user user-id}))

(defn send-message! [user body]
  (when-let [{:keys [channel-id bot-access-token]} (:slack user)]
    (tracef "tictag.slack/send-message! %s" (:username user))
    (slack-call! "chat.postMessage"
                 {:token   bot-access-token
                  :channel channel-id
                  :text    body})))

