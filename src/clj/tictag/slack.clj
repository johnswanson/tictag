(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [clojure.string :as str]
            [oauth.v2 :as v2]
            [taoensso.timbre :as timbre]
            [tictag.utils :as utils]))

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

(defn send-messages
  "Send one user MULTIPLE messages"
  [user messages]
  (when-let [{:keys [channel-id bot-access-token]} (:slack user)]
    (timbre/trace [:send-messages
                   {:user (:id user)
                    :messages messages}])
    (doall
     (map (fn [{:keys [text thread-ts]}]
            (future
              (let [resp @(http/post (method-url "chat.postMessage")
                                     {:form-params {:token           bot-access-token
                                                    :channel         channel-id
                                                    :text            text
                                                    :thread_ts       thread-ts
                                                    :reply_broadcast true}})]
                (if-not (utils/success? resp)
                  (timbre/error resp)
                  (timbre/trace (:status resp)))
                (when (utils/success? resp)
                  (assoc resp
                         :json (when-let [body (:body resp)]
                                 (json/parse-string body true)))))))
          messages))))

(defn send-message [user message]
  (send-messages user [{:text message}]))


(defn send-messages*
  "Send multiple users ONE message"
  [users body]
  (let [slack-users (->> users
                         (map :slack)
                         (map #(select-keys % [:user-id :bot-access-token :channel-id]))
                         (filter seq))]
    (map (fn [slack-user]
           [(:user-id slack-user)
            (future
              (let [resp (http/post (method-url "chat.postMessage")
                                    {:form-params {:token   (:bot-access-token slack-user)
                                                   :channel (:channel-id slack-user)
                                                   :text    body}})]
                (if-not (utils/success? @resp)
                  (timbre/error @resp)
                  (timbre/trace @resp))
                (when (utils/success? @resp)
                  (assoc @resp
                         :json (when-let [body (:body @resp)]
                                 (json/parse-string body true))))))])
         slack-users)))

(defn send-chime! [users id long-time]
  (send-messages* users (format "`ping %s [%s]`" id long-time)))
