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

(defn channels [token]
  (into
   {}
   (map (juxt :name_normalized :id)
        (:channels
         (slack-call! "channels.list" {:token token})))))

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
            (future
              (let [resp @(http/post (method-url "chat.postMessage")
                                     {:form-params {:token           bot-access-token
                                                    :channel         (or channel dm-id)
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

(defn send-message!
  [params]
  (future
    (let [resp (http/post (method-url "chat.postMessage")
                          {:form-params params})]
      (if-not (utils/success? @resp)
        (timbre/error @resp)
        (timbre/trace @resp))
      (when (utils/success? @resp)
        (assoc @resp
               :json (when-let [body (:body @resp)]
                       (json/parse-string body true)))))))

(defn send-messages*
  "Send multiple users ONE message"
  [slacks body]
  (->> slacks
       (map #(assoc % :text body))
       (map send-message!)))

(defn send-chime! [slacks id long-time]
  (send-messages* slacks (format "`ping %s [%s]`" id long-time)))
