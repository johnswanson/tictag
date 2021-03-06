(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [clojure.string :as str]
            [oauth.v2 :as v2]
            [taoensso.timbre :as timbre]
            [tictag.utils :as utils]
            [diehard.core :as dh]))

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
  (when (and (utils/success? resp)
             (get-in resp [:body :ok]))
    resp))

(dh/defretrypolicy retry-policy
  {:backoff-ms [11 1009]
   :jitter-factor 0.1
   :max-retries 8})


(defn post [client cmd opts]
  (timbre/debug cmd)
  (dh/with-retry {:policy retry-policy
                  :on-retry (fn [_ e] (timbre/debug "Retry" cmd (.getMessage e)))
                  :on-abort (fn [_ e] (timbre/error "Retries failed, aborting" cmd (.getMessage e)))}
    (let [{:keys [error] :as response} (-> cmd
                                           (method-url)
                                           (http/post opts)
                                           (deref 5000 {:error :timeout}))]
      (when error
        (throw (ex-info "HTTP Exception occurred" {:exception error
                                                   :cmd cmd
                                                   :opts opts})))
      (some-> response
              (update :body json/parse-string true)
              (success?)))))


(defn im-open [client token user-id]
  (:body (post client "im.open" {:form-params {:token token :user user-id}})))

(defn users-info [client token user-id]
  (:body (post client "users.info" {:form-params {:token token :user user-id}})))

(defn channels [client token]
  (into {}
        (map (juxt :name_normalized :id)
             (-> (post "channels.list" client {:form-params {:token token}})
                 :body
                 :channels))))

(defn channel-id [token channel-name]
  (get (channels token) channel-name))

(defn send-messages
  "Send one user MULTIPLE messages"
  [client user messages]
  (when-let [{:keys [dm-id bot-access-token]} (:slack user)]
    (timbre/debug "Sending messages" {:user (:id user)
                                      :messages messages})
    (doseq [{:keys [text thread-ts channel]} messages]
      (post client
            "chat.postMessage"
            {:form-params {:token           bot-access-token
                           :channel         (or channel dm-id)
                           :text            text
                           :thread_ts       thread-ts
                           :reply_broadcast true}}))))

(defn send-message [client user message]
  (send-messages client user [{:text message}]))

(defn send-message!
  [client params]
  (post client "chat.postMessage" {:form-params params}))
