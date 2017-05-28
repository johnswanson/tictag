(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [re-com.core :as re-com]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [tictag.views.inputs :refer [input-timezone]]
            [goog.string :as str]
            [cljs.reader :as edn]))

(defn tagtime-import []
  (let [model (reagent/atom "")]
    [re-com/v-box
     :children [[re-com/title :level :level1 :label "Import from TagTime"]
                [re-com/box
                 :child [:form {:on-submit #(.preventDefault %)}
                         [:label {:for "upload"
                                  :style {:cursor :pointer}}
                          [:span.rc-button.btn.btn-default [:i.zmdi.zmdi-cloud-upload {:style {:margin-right "1em"}}] "Upload Log"]]
                         [:input#upload
                          {:type "file" :name "file" :style {:width "0px" :height "0px" :opacity 0 :overflow :hidden :position :absolute :z-index -1}
                           :on-change #(dispatch [:tagtime-import/file (-> % .-target .-files (aget 0))])}]]]]]))

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
                  [slack-authorize])
                [re-com/title :level :level2 :label "Slackbot instructions:"]
                [re-com/p "Use the " [:code "help"] " command to see the available commands, like:"]
                [:ol
                 [:li [re-com/p "Just send something like " [:code "foo bar"] " to tag the most recent ping as " [:code (pr-str ["foo" "bar"])]]]
                 [:li [re-com/p "Refer to a recent ping by its ID, like " [:code "123 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]]
                 [:li [re-com/p "Refer to any ping by its ms-from-epoch timestamp, like " [:code "1495753682000 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]]
                 [:li [re-com/p "The special command " [:code "sleep"] " will tag the last contiguous series of pings as " [:code (pr-str "sleep")]]]
                 [:li [re-com/p "Send a single " [:code "\""] " to 'ditto'--just tag the last ping with the same tags as the second-to-last ping"]]]]]))

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
                   :children [[re-com/title :level :level2 :label "Warning!"]
                              [re-com/p "DO NOT point TicTag at any Beeminder goal that already has data, unless you're okay losing it."]
                              [re-com/p "TicTag assumes that it is the sole source of truth for your Beeminder data."]
                              [re-com/p
                               "This means we will delete any data points that don't have corresponding TicTag pings! "
                               "(At some point soon we'll just sync data points within the past week. But this isn't done yet.)"]
                              [re-com/checkbox
                               :model (:enabled? @beeminder-sub)
                               :on-change #(dispatch [:beeminder/enable? %])
                               :label-style {:font-weight "bold"}
                               :label "I have read the above and want to enable Beeminder sync."]
                              [re-com/label
                               :label [:span "Beeminder user: " [:b (:username @beeminder-sub)]]]
                              [beeminder-goals @goals]
                              [add-beeminder-goal-button]
                              [delete-beeminder-button]]]
                  [beeminder-token-input])]]))

(defn timezone []
  (let [timezone          (subscribe [:timezone])
        allowed-timezones (subscribe [:allowed-timezones])]
    [re-com/v-box
     :children [[re-com/title :level :level1 :label "Change time zone"]
                [re-com/single-dropdown
                 :width "100%"
                 :choices (map (fn [tz] {:id tz :label tz}) @allowed-timezones)
                 :model @timezone
                 :filter-box? true
                 :on-change #(dispatch [:settings/changed-timezone %])]]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    [re-com/v-box
     :children [[timezone]
                [tagtime-import]
                [beeminder]
                [slack]]]))


