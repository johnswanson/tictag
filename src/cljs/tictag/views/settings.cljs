(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe]]))

(defn slack-auth-button [auth-user]
  (when-not (:slack auth-user)
    [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}

     [:img {:alt      "Add to Slack"
            :height   40
            :width    "139"
            :src      "https://platform.slack-edge.com/img/add_to_slack.png"
            :src-set= "https://platform.slack.edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png"}]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    (fn []
      [:div [slack-auth-button @auth-user]])))


