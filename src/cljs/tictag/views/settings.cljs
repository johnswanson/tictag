(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [re-com.core :as re-com]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [tictag.beeminder :as beeminder]
            [goog.string :as str]))


(defn slack-authorize []
  [:div
   [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}
    [:img {:alt     "Add to Slack"
           :height  40
           :width   "139"
           :src     "https://platform.slack-edge.com/img/add_to_slack.png"
           :src-set "https://platform.slack-edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]])

(defn slack-auth-button [& {:keys [delete user]}]
  [:label "Slack Authorization"
   (if-let [slack (:slack user)]
     [:div
      [re-com/hyperlink
       :on-click #(delete)
       :tooltip "Click to delete"
       :label [re-com/label :label (:username slack)]]]
     [slack-authorize])])

(defn match? [parsed text]
  (beeminder/match? parsed (set (map keyword (str/splitLimit text " " 10)))))

(defn beeminder-goals-editor [user]
  (let [goals (get-in user [:beeminder :goals])]
    [:pre (pr-str goals)]))

(defn beeminder-goal-editor [& {:keys [goal tags id save]}]
  (let [goal! (reagent/atom goal)
        tags! (reagent/atom (pr-str tags))
        test! (reagent/atom "")]
    (fn [& {:keys [goal tags id save]}]
      (let [parsed (beeminder/str->parsed @tags!)
            matched? (match? parsed @test!)]
        [re-com/h-box
         :align :center
         :children [[re-com/v-box
                     :children [[:pre {:style {:border-color (if matched?
                                                               "green" "black")}}
                                 (pr-str parsed)]
                                [re-com/h-box
                                 :children [[:label "Goal Name (as it appears in URL)"
                                             [re-com/input-text
                                              :model goal!
                                              :placeholder "workhard"
                                              :on-change #(reset! goal! %)]]
                                            [:label "Goal rules"
                                             [re-com/input-text
                                              :model tags!
                                              :placeholder "work or (coding and concentrating)"
                                              :on-change #(reset! tags! %)
                                              :change-on-blur? false]]
                                            [:label "Example ping response (optional)"
                                             [re-com/input-text
                                              :model test!
                                              :placeholder "work coding"
                                              :on-change #(reset! test! %)
                                              :status (cond
                                                        (not (seq @tags!)) nil
                                                        matched? :success
                                                        :default :error)
                                              :change-on-blur? false]]]]]]
                    [re-com/md-circle-icon-button
                     :md-icon-name "zmdi-refresh-sync-alert"
                     :on-click #(save @goal! @tags!)
                     :size :larger
                     :emphasise? true]]]))))

(defn beeminder [& {:keys [save delete user model]}]
  [:div
   (for [g (get-in user [:beeminder :goals])]
     ^{:key (:goal g)}
     [beeminder-goal-editor :goal (:goal g) :tags (:tags g) :save #(js/console.log %)])
   [:label "Beeminder Token"
    (if-let [current-token (get-in user [:beeminder :token])]
      [:div
       [re-com/hyperlink
        :on-click #(delete)
        :tooltip "Click to delete"
        :label [re-com/label :label current-token]]]
      [re-com/input-text
       :placeholder "Beeminder Token"
       :style {:border-radius "0px"}
       :width "100%"
       :model model
       :on-change #(reset! model %)])]])

(defn settings []
  (let [auth-user (subscribe [:authorized-user])
        beeminder-model (reagent/atom "")]
    (fn []
      [re-com/v-box
       :children [[beeminder
                   :save #(dispatch [:save-beeminder-token] @beeminder-model)
                   :delete #(dispatch [:delete-beeminder])
                   :user @auth-user
                   :model beeminder-model]
                  [slack-auth-button
                   :user @auth-user
                   :delete #(dispatch [:delete-slack])]]])))


