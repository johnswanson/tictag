(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [re-com.core :as re-com]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [goog.string :as str]
            [cljs.reader :as edn]))

(defn tagtime-import []
  (let [model (reagent/atom "")]
    [re-com/v-box
     :children [[re-com/title :level :level1 :label "Import from TagTime"]
                [re-com/input-textarea
                 :model model
                 :width "100%"
                 :on-change #(reset! model %)]
                [re-com/button
                 :label "Submit"
                 :on-click #(dispatch [:tagtime-import/send @model])]]]))

(defn slack-authorize []
  [:div
   [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}
    [:img {:alt     "Add to Slack"
           :height  40
           :width   "139"
           :src     "https://platform.slack-edge.com/img/add_to_slack.png"
           :src-set "https://platform.slack-edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]])

(defn slack []
  (let [slack (subscribe [:slack])]
    [re-com/v-box
     :children [[re-com/title :level :level1 :label "Slack"]
                (if (:username @slack)
                  [re-com/button
                   :label (str "Delete slack (authed as " (:username @slack) ")")
                   :on-click #(dispatch [:slack/delete])]
                  [slack-authorize])]]))

(defn beeminder-goal-editor [goal]
  [re-com/h-box
   :children [[re-com/input-text
               :model (or (:goal/name goal) "")
               :on-change #(dispatch [:goal/edit (:goal/id goal) :goal/name %])]
              [re-com/input-text
               :model (or (:goal/tags goal) "")
               :status (if (:goal/tags-valid? goal) nil :error)
               :placeholder "(and \"foo\" (or \"bar\" \"baz\" \"bin\"))"
               :on-change #(dispatch [:goal/edit (:goal/id goal) :goal/tags %])]
              [re-com/button
               :on-click #(dispatch [:goal/save (:goal/id goal)])
               :label "Save"]
              [re-com/button
               :on-click #(dispatch [:goal/delete (:goal/id goal)])
               :label "Delete"]]])

(defn beeminder-goals [goals]
  (when goals
    [re-com/v-box
     :children (for [goal goals]
                 ^{:key (:goal/id goal)}
                 [beeminder-goal-editor goal])]))

(defn add-beeminder-goal-button []
  (let [path [:goal/by-id :temp]
        goal (subscribe path)]
    (if @goal
      [beeminder-goal-editor @goal]
      [re-com/button
       :on-click #(dispatch [:goal/new])
       :label "Add Goal"])))

(defn beeminder-token-input []
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
                             :on-click #(dispatch [:beeminder-token/add @val])
                             :label "Save"]]]]]))

(defn delete-beeminder-button []
  [re-com/button
   :on-click #(dispatch [:beeminder-token/delete])
   :label "Delete Beeminder"])

(defn beeminder []
  (let [beeminder-sub (subscribe [:beeminder])
        goals         (subscribe [:beeminder-goals])]
    [re-com/v-box
     :children [[re-com/title
                 :level :level1
                 :label "Beeminder"]
                (if (:token @beeminder-sub)
                  [re-com/v-box
                   :children [[re-com/label
                               :label [:span "Beeminder user: " (:username @beeminder-sub)]]
                              [beeminder-goals @goals]
                              [add-beeminder-goal-button]
                              [delete-beeminder-button]]]
                  [beeminder-token-input])]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    [re-com/v-box
     :children [[tagtime-import]
                [beeminder]
                [slack]]]))


