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

(defn beeminder-token-input [path]
  (let [val (reagent/atom "")]
    [re-com/v-box
     :children [[re-com/label :label [:span
                                      "Add your Beeminder token here! (get your token "
                                      [re-com/hyperlink-href
                                       :href "https://www.beeminder.com/api/v1/auth_token.json"
                                       :label "here"
                                       :target "_blank"]
                                      ")"]]
                [re-com/h-box
                 :children [[re-com/input-text
                             :model val
                             :placeholder "Beeminder Token"
                             :on-change #(reset! val %)]
                            [re-com/button
                             :on-click #(dispatch [:beeminder-token/add path @val])
                             :label "Save"]]]]]))

(defn delete-beeminder-button [path]
  [re-com/button
   :on-click #(dispatch [:beeminder-token/delete path])
   :label "Delete Beeminder"])

(defn beeminder [path]
  (let [beeminder-sub (subscribe [:beeminder path])]
    [re-com/v-box
     :children [[re-com/title
                 :level :level1
                 :label "Beeminder"]
                (if (:token @beeminder-sub)
                  [re-com/v-box
                   :children [[re-com/label
                               :label [:span "Beeminder user: " (:username @beeminder-sub)]]
                              [beeminder-goals (:goals @beeminder-sub)]
                              [add-beeminder-goal-button]
                              [delete-beeminder-button path]]]
                  [beeminder-token-input path])]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    [re-com/v-box
     :children [[beeminder (:beeminder @auth-user)]
                [slack-auth-button (:slack @auth-user)]]]))


