(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [re-com.core :as re-com]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [tictag.views.inputs :refer [input-timezone]]
            [goog.string :as str]
            [cljs.reader :as edn]
            [taoensso.timbre :as timbre]))

(defn tagtime-upload-progress-view [name u]
  [re-com/v-box
   :children [[re-com/title :level :level3 :label name]
              [re-com/title :level :level4 :label "Upload Progress"]
              [re-com/progress-bar
               :bar-class "error"
               :model (:upload-progress u)]
              (when (:process-progress u)
                [:div
                 [re-com/title :level :level4 :label "Processing Progress"]
                 [re-com/progress-bar
                  :model (:process-progress u)]])]])

(defn tagtime-upload-progress [fname]
  (let [sub (subscribe [:db/tagtime-upload fname])]
    [tagtime-upload-progress-view fname @sub]))

(defn tagtime-upload-progress-all []
  (let [subs (subscribe [:db/tagtime-uploads])]
    [re-com/v-box
     :children
     (for [fname @subs]
       ^{:key fname} [tagtime-upload-progress fname])]))

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
                           :on-change #(dispatch [:tagtime-import/file (-> % .-target .-files (aget 0))])}]]]
                [tagtime-upload-progress-all]]]))

(defn slack-authorize []
  [:div
   [:a {:href (str "https://slack.com/oauth/authorize?scope=bot,users:read&client_id=" js/slack_client_id)}
    [:img {:alt     "Add to Slack"
           :height  40
           :width   "139"
           :src     "https://platform.slack-edge.com/img/add_to_slack.png"
           :src-set "https://platform.slack-edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]])

(defn slack-help []
  [re-com/v-box
   :children
   [[re-com/title :level :level2 :label "Slackbot instructions:"]
    [re-com/p "Every time a ping goes out, you'll get a slack message like: "]
    [re-com/p [:code "ping 123 [1495753682000]"]]
    [re-com/p "To respond, you can:"]
    [:ol
     [:li [re-com/p "Just send something like " [:code "foo bar"] " to tag the most recent ping as " [:code (pr-str ["foo" "bar"])]]]
     [:li [re-com/p "Using the same syntax, respond to a ping in a thread to respond to that specific ping"]]
     [:li [re-com/p "Refer to a recent ping by its ID, like " [:code "123 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]]
     [:li [re-com/p "Refer to any ping by its ms-from-epoch timestamp, like " [:code "1495753682000 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]]
     [:li [re-com/p "The special command " [:code "sleep"] " will tag the last contiguous series of pings as " [:code (pr-str ["sleep"])]]]
     [:li [re-com/p [:code "\""] " will macro-expand to 'ditto'--in other words, the tags that the " [:span.bold "previous"] " ping had"]]
     [:li [re-com/p "You can send multiple commands at once by separating them with newlines"]]]
    [re-com/p "You can also send " [:code "help"] " to the slackbot if you need it."]]])

(defn slack-preferences-component [dispatch slack slack-errors]
  (let [{:keys [dm-id channel-id dm? channel? username channel-name] :as slack} @slack
        errors                                                                  @slack-errors]
    (timbre/debug slack)
    (if username
      [re-com/v-box
       :children [[re-com/title :level :level2 :label "Remove slackbot"]
                  [re-com/button
                   :label (str "Delete slack (authed as " username ")")
                   :on-click #(dispatch [:slack/delete])]
                  [re-com/title :level :level2 :label "Slack notification preferences"]
                  [re-com/checkbox
                   :model (or dm? false)
                   :on-change #(dispatch [:slack/update (:id slack) :dm? %])
                   :label "Direct message"]
                  [re-com/h-box
                   :align :center
                   :children
                   [[re-com/checkbox
                     :model (or channel? false)
                     :on-change #(dispatch [:slack/update (:id slack) :channel? %])]
                    [re-com/label :label "Channel" :style {:padding-left  "8px"
                                                           :padding-right "8px"}]
                    [re-com/input-text
                     :placeholder "#channel"
                     :model (or channel-name "")
                     :status (when (:channel-name errors) :error)
                     :status-tooltip (or (:channel-name errors) "")
                     :status-icon? true
                     :on-change #(dispatch [:slack/update (:id slack) :channel-name %])]]]]]
      [slack-authorize])))

(defn slack-preferences []
  (let [slack        (subscribe [:slack])
        slack-errors (subscribe [:slack-errors])]
    [slack-preferences-component dispatch slack slack-errors]))

(defn slack []
  [re-com/v-box
   :children [[re-com/title :level :level1 :label "Slack"]
              [slack-preferences]
              [slack-help]]])


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
                              [re-com/p "Pointing TicTag at an existing Beeminder goal is dangerous!"]
                              [re-com/p
                               "TicTag assumes we're the only source of truth for data within the past week. "
                               "This basically means that your last week's worth of Beeminder data (whatever its source) will be replaced "
                               "with the last week's worth of TicTag's data."]
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

(defn download []
  (let [pings (subscribe [:raw-pings])
        blob  (js/Blob. #js [(js/JSON.stringify (clj->js @pings))] #js {:type "text/json"})
        url   (.createObjectURL js/window.URL blob)]
    [re-com/v-box
     :children [[re-com/title :level :level1 :label "Download your data"]
                [re-com/box
                 :child [:label
                         [:a {:download "tictag.json"
                              :href url}
                          [:span.rc-button.btn.btn-default
                           [:i.zmdi.zmdi-cloud-download {:style {:cursor :pointer
                                                                 :margin-right "1em"}}]
                           "Download"]]]]]]))

(defn settings []
  (let [auth-user (subscribe [:authorized-user])]
    [re-com/v-box
     :children [[slack]
                [beeminder]
                [timezone]
                [tagtime-import]
                [download]]]))


