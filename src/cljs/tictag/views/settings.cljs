(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [re-com.core :as re-com]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [tictag.beeminder :as beeminder]
            [goog.string :as str]
            [cljs.reader :as edn]))


(defn slack-authorize []
  [:div
   [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}
    [:img {:alt     "Add to Slack"
           :height  40
           :width   "139"
           :src     "https://platform.slack-edge.com/img/add_to_slack.png"
           :src-set "https://platform.slack-edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]])

(defn slack-auth-button [path]
  [:label "Slack Authorization"
   (let [sub (subscribe [:slack path])]
     (js/console.log "SUB: " @sub)
     (if-let [slack @sub]
       [:div
        [re-com/hyperlink
         :on-click #(dispatch [:slack/delete path])
         :tooltip "Click to delete"
         :label [re-com/label :label (:username slack)]]]
       [slack-authorize]))])

(defn beeminder-goal-editor [goal-path]
  (let [goal (subscribe goal-path)]
    [re-com/h-box
     :children [[re-com/input-text
                 :model (or (:goal/name @goal) "")
                 :on-change #(dispatch [:goal/edit (conj goal-path :goal/name) %])]
                [re-com/input-text
                 :model (or (:goal/tags @goal) "")
                 :on-change #(dispatch [:goal/edit (conj goal-path :goal/tags) %])]
                [re-com/button
                 :on-click #(dispatch [:goal/save goal-path])
                 :label "Save"]
                [re-com/button
                 :on-click #(dispatch [:goal/delete goal-path])
                 :label "Delete"]]]))

(defn beeminder-goals [goals]
  (when goals
    [re-com/v-box
     :children (for [[_ id :as goal] goals]
                 ^{:key id}
                 [beeminder-goal-editor goal])]))

(defn add-beeminder-goal-button []
  (let [path [:goal/by-id :temp]
        goal (subscribe path)]
    (if @goal
      [beeminder-goal-editor path]
      [re-com/button
       :on-click #(dispatch [:goal/new])
       :label "Add Goal"])))

(defn beeminder [path]
  (let [beeminder-sub (subscribe [:beeminder path])]
    [re-com/v-box
     :children [[beeminder-goals (:goals @beeminder-sub)]
                [add-beeminder-goal-button]]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    [re-com/v-box
     :children [[beeminder (:beeminder @auth-user)]
                [slack-auth-button (:slack @auth-user)]]]))


