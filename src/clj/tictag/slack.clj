(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [clojure.string :as str]
            [oauth.v2 :as v2]
            [taoensso.timbre :as timbre]
            [tictag.utils :as utils]
            [diehard.core :as dh]
            [com.climate.claypoole :as cp]))

(defrecord SlackClient []
  component/Lifecycle
  (start [component]
    (assoc component ::threadpool (cp/threadpool 100)))
  (stop [component]
    (when-let [pool (:threadpool component)]
      (cp/shutdown pool))
    (dissoc component ::threadpool)))

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

(dh/defretrypolicy retry-policy
  {:backoff-ms [250 5000]
   :max-retries 8
   :retry-if (fn [{:keys [error]} exception]
               (or error exception))})

(defn post [client cmd opts]
  (cp/future
    (::threadpool client)
    (dh/with-retry {:policy retry-policy}
      (let [resp (-> cmd
                     (method-url)
                     (http/post opts)
                     (deref)
                     (update :body json/parse-string true))]
        (when (success? resp)
          resp)))))

(defn im-open [client token user-id]
  (:body @(post client "im.open" {:form-params {:token token :user user-id}})))

(defn users-info [client token user-id]
  (:body @(post client "users.info" {:form-params {:token token :user user-id}})))

(defn channels [client token]
  (into {}
        (map (juxt :name_normalized :id)
             (-> (post "channels.list" client {:form-params {:token token}})
                 deref
                 :body
                 :channels))))

(defn channel-id [token channel-name]
  (get (channels token) channel-name))

(defn send-messages
  "Send one user MULTIPLE messages"
  [client user messages]
  (when-let [{:keys [dm-id bot-access-token]} (:slack user)]
    (timbre/trace [:send-messages
                   {:user (:id user)
                    :messages messages}])
    (doall
     (map (fn [{:keys [text thread-ts channel]}]
            (post client
                  "chat.postMessage"
                  {:form-params {:token           bot-access-token
                                 :channel         (or channel dm-id)
                                 :text            text
                                 :thread_ts       thread-ts
                                 :reply_broadcast true}}))
          messages))))

(defn send-message [client user message]
  (send-messages client user [{:text message}]))

(defn send-message!
  [client params]
  (post client "chat.postMessage" {:form-params params}))

(defn send-messages*
  "Send multiple users messages"
  [client slacks bodies]
  (->> (map #(assoc %1 :text %2) slacks bodies)
       (map (partial send-message! client))))

(defn send-chime! [client slacks messages]
  (send-messages* client slacks messages))
