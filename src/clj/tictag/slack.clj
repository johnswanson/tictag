(ns tictag.slack
  (:require [org.httpkit.client :as http]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def slack-api-url "https://slack.com/api/")
(defn method-url [m] (str slack-api-url m))
(defn slack-call! [cmd params]
  (-> (method-url cmd)
      (http/post {:form-params params})
      deref
      :body
      (json/parse-string true)))

(defn send-message! [user body]
  (when-let [{:keys [channel-id bot-access-token]} (:slack user)]
    (slack-call! "chat.postMessage"
                 {:token   bot-access-token
                  :channel channel-id
                  :text    body})))

(defn valid-event? [{:keys [config]} outer-event]
  (= (:verification-token config) (:token outer-event)))

