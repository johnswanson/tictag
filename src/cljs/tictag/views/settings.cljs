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

(defn rule-box [& {:keys [model on-change]}]
  [:label "Goal rules"
   [re-com/input-text
    :model model
    :placeholder "[:or :work [:and :coding :concentrating]]"
    :on-change #(reset! model %)
    :change-on-blur? false]])

(defn goal-box [& {:keys [model on-change]}]
  [:label "Goal Name (as it appears in URL)"
   [re-com/input-text
    :model model
    :placeholder "workhard"
    :change-on-blur? false
    :on-change #(reset! model %)]])

(defn test-box [& {:keys [model on-change status]}]
  [:label "Example ping response (optional)"
   [re-com/input-text
    :model model
    :placeholder "work coding"
    :on-change #(reset! model %)
    :status (status)
    :change-on-blur? false]])

(defn save-button [original-goal new-goal save]
  (if (= original-goal new-goal)
    [:div]
    [re-com/md-circle-icon-button
     :md-icon-name "zmdi-refresh-sync"
     :on-click #(save new-goal)
     :size :larger
     :emphasise? true]))

(defn beeminder-goal-editor [& {:keys [goal tags id save]}]
  (let [goal! (reagent/atom goal)
        tags! (reagent/atom (pr-str tags))
        test! (reagent/atom "")]
    (fn [& {:keys [goal tags id save]}]
      [re-com/v-box
       :align :center
       :style {:border  "1px solid black"
               :padding "8px"}
       :children [[save-button
                   {:goal goal :tags tags :id id}
                   {:goal @goal! :tags (try (edn/read-string @tags!) (catch js/Error _ nil)) :id id}
                   save]
                  [re-com/h-box
                   :gap "10px"
                   :children [[goal-box
                               :model goal!
                               :on-change #(reset! goal! %)]
                              [rule-box
                               :model tags!
                               :on-change #(reset! tags! %)]
                              [test-box
                               :model test!
                               :on-change #(reset! test! %)
                               :status (fn []
                                         (let [parsed-tags (try (edn/read-string @tags!) (catch js/Error _ nil))]
                                           (cond
                                             (not (seq @test!))                                                                nil
                                             (beeminder/match? parsed-tags (set (map keyword (str/splitLimit @test! " " 10)))) :success
                                             :else                                                                             :error)))]]]]])))

(defn beeminder [& {:keys [save delete user model]}]
  [:div
   (for [g (get-in user [:beeminder :goals])]
     ^{:key (:goal g)}
     [beeminder-goal-editor
      :goal (:goal g)
      :tags (:tags g)
      :id   (:id g)
      :save #(dispatch [:save-goal %])])
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


