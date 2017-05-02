(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [tictag.constants :refer [ENTER]]))

(defn slack-authorize []
  [:div
   [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}
    [:img {:alt     "Add to Slack"
           :height  40
           :width   "139"
           :src     "https://platform.slack-edge.com/img/add_to_slack.png"
           :src-set "https://platform.slack.edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]])

(defn slack-deauthorize [slack]
  [:div
   [:div (str "Looks like you're \"" (:username slack) "\" on slack.")]
   [:button {:on-click #(dispatch [:delete-slack])}
   "Remove Slack Authorization"]])

(defn slack-auth-button [auth-user]
  (if-let [slack (:slack auth-user)]
    [slack-deauthorize slack]
    [slack-authorize]))

(defn beeminder [save edit delete]
  (let [temp-beeminder-token (subscribe [:temp-beeminder-token])]
    (fn []
      [:div
       [:label
        "Beeminder Token"
        [:a {:href "https://www.beeminder.com/api/v1/auth_token.json"} "Get your token!"]
        [:input {:type        :text
                 :value       @temp-beeminder-token
                 :on-change   #(edit (.. % -target -value))
                 :on-key-down #(condp = (.. % -which)
                                 ENTER (save)
                                 nil)}]]
       [:button {:on-click #(save)} "Save"]
       [:button {:on-click #(delete)} "Delete"]])))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    (fn []
      [:div
       [:pre (pr-str @auth-user)]
       [beeminder
        #(dispatch [:save-beeminder-token])
        #(dispatch [:edit-beeminder-token %])
        #(dispatch [:delete-beeminder])]
       [slack-auth-button @auth-user]])))


