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

(defn success? [resp]
  (if (utils/success? resp)
    (get-in resp [:body :ok])))

(defn post [cmd opts]
  (let [resp (http/post (method-url cmd) opts)]
    (future
      (let [resp (update @resp :body json/parse-string true)]
        (if-not (success? resp)
          (timbre/error [:slack-error-resp resp])
          (timbre/trace [:slack-resp resp]))
        (when (success? resp)
          resp)))))

(defn im-open [token user-id]
  (:body @(post "im.open" {:form-params {:token token :user user-id}})))

(defn users-info [token user-id]
  (:body @(post "users.info" {:form-params {:token token :user user-id}})))

(defn channels [token]
  (into {}
        (map (juxt :name_normalized :id)
             (-> "channels.list"
                 (post {:form-params {:token token}})
                 deref
                 :body
                 :channels))))

(defn channel-id [token channel-name]
  (get (channels token) channel-name))

(defn send-messages
  "Send one user MULTIPLE messages"
  [user messages]
  (when-let [{:keys [dm-id bot-access-token]} (:slack user)]
    (timbre/trace [:send-messages
                   {:user (:id user)
                    :messages messages}])
    (doall
     (map (fn [{:keys [text thread-ts channel]}]
            (post "chat.postMessage"
                  {:form-params {:token           bot-access-token
                                 :channel         (or channel dm-id)
                                 :text            text
                                 :thread_ts       thread-ts
                                 :reply_broadcast true}}))
          messages))))

(defn send-message [user message]
  (send-messages user [{:text message}]))

(defn send-message!
  [params]
  (post "chat.postMessage" {:form-params params}))

(defn send-messages*
  "Send multiple users ONE message"
  [slacks body]
  (->> slacks
       (map #(assoc % :text body))
       (map send-message!)))

(defn send-chime! [slacks id long-time]
  (send-messages* slacks (format "`ping %s [%s]`" id long-time)))
