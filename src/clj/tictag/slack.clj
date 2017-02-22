(ns tictag.slack
  (:require [slack-rtm.core :as slack]
            [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [tictag.cli :refer [handle-command]]
            [tictag.db :as db]
            [clojure.string :as str]))

(defn dispatcher [slack]
  (:dispatcher (:conn slack)))

(defn pub [slack]
  (:events-publication (:conn slack)))

(defn send-message! [slack body]
  (let [{:keys [dm-channel-id]} slack]
    (slack/send-event (dispatcher slack)
                      {:type "message"
                       :text body
                       :channel dm-channel-id})))

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
    (let [user-list     (-> "https://slack.com/api/users.list"
                            (http/get {:query-params {:token (:token config)}})
                            deref
                            :body
                            (json/parse-string true)
                            :members)
          [my-user]     (filter #(= (:name %) (:username config)) user-list)
          dm-channel-id (-> "https://slack.com/api/im.open"
                            (http/get {:query-params {:token (:token config)
                                                      :user  (:id my-user)}})
                            deref
                            :body
                            (json/parse-string true)
                            :channel
                            :id)
          conn          (slack/connect (:token config))
          new-component (assoc component
                               :user my-user
                               :conn conn
                               :dm-channel-id dm-channel-id)]
      (slack/sub-to-event (:events-publication conn) :message (partial handle-message new-component))
      new-component))
  (stop [component]
    (when-let [conn (:conn component)]
      (slack/send-event (:dispatcher conn) :close))))

