(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [tictag.cli :refer [handle-command]]
            [tictag.db :as db]
            [clojure.string :as str]))

(def slack-api-url "https://slack.com/api/")
(defn method-url [m] (str slack-api-url m))
(defn slack-call! [cmd params]
  (-> (method-url cmd)
      (http/post {:form-params params})
      deref
      :body
      (json/parse-string true)))


(defn send-message! [slack body]
  (let [{:keys [dm-channel-id config]} slack]
    (slack-call! "chat.postMessage"
                 {:token   (:token config)
                  :channel dm-channel-id
                  :text    body})))

(defn handle-message [component message]
  (when (and (= (:dm-channel-id component) (:channel message))
             (= (:id (:user component)) (:user message)))
    (handle-command component (:text message))
    (send-message! component (->> component
                                  (:db)
                                  (:db)
                                  (db/get-pings)
                                  (reverse)
                                  (map pr-str)
                                  (map #(format "```%s```" %))
                                  (take 5)
                                  (str/join "\n")))))

(defrecord Slack [config db]
  component/Lifecycle
  (start [component]
    (let [user-list     (:members (slack-call! "users.list" {:token (:token config)}))
          [my-user]     (filter #(= (:name %) (:username config)) user-list)
          dm-channel-id (:id
                         (:channel
                          (slack-call!
                           "im.open"
                           {:token (:token config)
                            :user  (:id my-user)})))]
      (assoc component :user my-user :dm-channel-id dm-channel-id)))
  (stop [component] component))

