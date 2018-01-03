(ns tictag.views.settings
  (:require [re-frame.core :refer [subscribe dispatch]]
            [tictag.views.common :refer [input]]
            [tictag.utils :as utils :refer [dispatch-n]]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [goog.string :as str]
            [cljs.reader :as edn]
            [taoensso.timbre :as timbre]))

(defn tagtime-upload-progress-view [name u]
  [:div
   [:h3 name]
   [:h4 "Upload Progress"]
   [:div.progress
    [:div.progress-bar {:style {:width (str (:upload-progress u) "%")}}]]
   (when (:process-progress u)
     [:div
      [:h4 "Processing Progress"]
      [:div.progress
       [:div.progress-bar {:style {:width (str (:process-progress u) "%")}}]]])])

(defn tagtime-upload-progress [fname]
  (let [sub (subscribe [:db/tagtime-upload fname])]
    [tagtime-upload-progress-view fname @sub]))

(defn tagtime-upload-progress-all []
  (let [subs (subscribe [:db/tagtime-uploads])]
    [:div
     (for [fname @subs]
       ^{:key fname} [tagtime-upload-progress fname])]))

(defn tagtime-import []
  (let [model (reagent/atom "")]
    [:div
     [:h1 "Import from TagTime"]
     [:form {:on-submit #(.preventDefault %)}
      [:span.file-button
       [:input#upload {:type :file
                       :on-change #(dispatch [:tagtime-import/file (-> % .-target .-files (aget 0))])}]
       [:label.button {:for "upload"}
        [:i.fa-fa-cloud-upload] "Upload Log"]]
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
  [:div
   [:h2 "Slackbot instructions:"]
   [:p "Every time a ping goes out, you'll get a slack message like " [:code "ping 689 [1510831434000]"] ". To respond, you can: "]
   [:ol
    [:li "Just send something like " [:code "foo bar"] " to tag the most recent ping as " [:code (pr-str ["foo" "bar"])]]
    [:li "Using the same syntax, respond to a ping in a thread to respond to that specific ping"]
    [:li "Refer to a recent ping by its ID, like " [:code "123 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]
    [:li "Refer to any ping by its ms-from-epoch timestamp, like " [:code "1495753682000 foo bar"] ", to tag that ping as " [:code (pr-str ["foo" "bar"])]]
    [:li "The special command " [:code "sleep"] " will tag the last contiguous series of " [:code "afk"] " pings as " [:code (pr-str ["sleep"])]]
    [:li "Similarly, " [:code "! job"] " will tag the last contiguous set of " [:code "afk"] " pings as " [:code (pr-str ["job"])]]
    [:li [:code "\""] " will macro-expand to 'ditto'--in other words, the tags that the " [:span.bold "previous"] " ping had"]
    [:li "You can send multiple commands at once by separating them with newlines"]]
   [:p "You can also send " [:code "help"] " to the slackbot if you need it."]])

(defn slack-preferences-component [dispatch slack slack-errors]
  (let [slack  @slack
        errors @slack-errors]
    (if-let [username (:slack/username slack)]
      (let [{:keys [slack/dm? slack/channel? slack/id slack/channel-name]} slack]
        [:div
         [slack-help]
         [:h2 "Slack notification preferences"]
         [:div
          [:span.switch.switch-sm
           [:input#slack-dm (let [[_ dm?] (or (find slack :pending-slack/dm?)
                                              (find slack :slack/dm?))]
                              {:type :checkbox
                               :checked dm?
                               :on-change #(dispatch-n [:slack/update id :dm? (not dm?)]
                                                       [:slack/save id])})]
           [:label {:for "slack-dm"} "Direct Message"]]]
         [:span.switch.switch-sm
          [:input#slack-ch (let [[_ ch?] (or (find slack :pending-slack/channel?)
                                             (find slack :slack/channel?))]
                             {:type :checkbox
                              :checked ch?
                              :on-change #(dispatch-n [:slack/update id :channel? (not ch?)]
                                                      [:slack/save id])
                              :label "Channel"})]
          [:label {:for "slack-ch"} "Channel"]]
         [:span " "]
         [:div.input-field
          [:label "Channel"]
          [input {:v (or (:pending-slack/channel-name slack)
                         (:slack/channel-name slack)
                         "")
                  :type :text
                  :on-save #(dispatch [:slack/update id :channel-name %])
                  :on-stop #(dispatch [:slack/save id])}]]
         [:h2 "Remove Slack"]
         [:button {:type :button
                   :on-click #(dispatch [:slack/delete (:slack/id slack)])}
          (str "Delete slack (authed as " username ")")]])
      [slack-authorize])))

(defn slack-preferences []
  (let [slack-id (subscribe [:slack-id])
        slack    (subscribe [:slack @slack-id])
        errors   (subscribe [:slack-errors @slack-id])]
    [slack-preferences-component dispatch slack errors]))

(defn slack []
  [:div [:h1 "Slack"] [slack-preferences]])


(defn goal-editor [id]
  (let [{pending-goal :pending-goal/goal
         pending-tags :pending-goal/tags
         :keys        [goal/goal goal/tags]} @(subscribe [:goal id])]
    (let [goal-errs (and (seq pending-goal) (not= goal pending-goal))
          tags-errs (and (seq pending-tags) (not= tags pending-tags))]
      [:div.input-group
       [input {:type            :text
               :placeholder     "goal slug @ beeminder"
               :v               (or pending-goal goal "")
               :on-enter        #(dispatch [:goal/save id])
               :on-save         #(dispatch [:goal/update id :goal %])}]
       [input {:type            :text
               :placeholder     "tags"
               :v               (or pending-tags tags "")
               :on-enter        #(dispatch [:goal/save id])
               :on-save         #(dispatch [:goal/update id :tags %])}] 
       [:button.button-success
        {:type     :button
         :on-click #(dispatch [:goal/save id])}
        "Save"]
       (when (not= id :temp)
         [:button.button-light
          {:type     :button
           :on-click #(dispatch [:goal/delete id])}
          "Delete"])])))

(defn beeminder-goals [goals]
  [:div
   (for [id @goals]
     ^{:key id}
     [goal-editor id])
   [goal-editor :temp]])

(defn beeminder-token-input [errs]
  (let [token (reagent/atom "")]
    [:div
     [:p "Add your beeminder token to get stung if you don't spend your time wisely! (get your token "
      [:a {:href "https://www.beeminder.com/api/v1/auth_token.json"
           :target "_blank"}
       "here"]
      ")"]
     [:div.input-group
      [input {:v @token
              :on-save #(reset! token %)
              :on-stop #(dispatch [:beeminder/update :temp :beeminder/token @token])
              :on-enter #(dispatch [:beeminder/save :temp])}]
      [:button.button-success
       {:type :button
        :on-click #(dispatch [:beeminder/save :temp])}
       "Save"]]]))

(defn delete-beeminder-button [id]
  [:button.button-danger
   {:on-click #(dispatch [:beeminder/delete id])
    :type :button}
   "Delete Beeminder"])

(defn beeminder-create [id]
  (let [errs (subscribe [:beeminder-errors id])]
    [beeminder-token-input @errs]))

(defn beeminder-edit [id]
  (let [bm     (subscribe [:beeminder id])
        errors (subscribe [:beeminder-errors id])
        goals  (subscribe [:goals])]
    [:div
     [:h2 "Warning!"]
     [:p
      "Pointing Tictag at an existing goal is dangerous--your last week's worth of Beeminder data (whatever the source) will be replaced "
      "with the last week's worth of Tictag's data. You could " [:bold "derail"]]
     [:span.switch.switch-sm
      [:input#beeminder (let [checked? (or (:pending-beeminder/enabled? @bm)
                                           (:beeminder/enabled? @bm))]
                          {:type :checkbox
                           :checked checked?
                           :on-change #(dispatch-n [:beeminder/update (:beeminder/id @bm) :beeminder/enabled? (not checked?)]
                                                   [:beeminder/save (:beeminder/id @bm)])})]
      [:label {:for "beeminder"} "Enable Beeminder Sync for user " [:b (:beeminder/username @bm)]]]
     [beeminder-goals goals]
     [delete-beeminder-button (:beeminder/id @bm)]]))

(defn beeminder []
  (let [id (subscribe [:beeminder-id])]
    [:div
     [:h1 "Beeminder"]
     (if @id
       [beeminder-edit @id]
       [beeminder-create :temp])]))


(defn timezone []
  (let [user              (subscribe [:authorized-user])
        allowed-timezones (subscribe [:allowed-timezones])
        client-tz         (utils/local-tz)]
    [:div
     [:h1 "Timezone"]
     [:div.input-group
      [:select {:value     (:user/tz @user)
                :on-change #(dispatch-n [:user/update (:user/id @user) :user/tz (-> % .-target .-value)]
                                        [:user/save (:user/id @user)])}
       (for [tz @allowed-timezones]
         ^{:key tz} [:option tz])]
      (when (and client-tz ((set @allowed-timezones) client-tz))
        [:button.button {:type     :button
                         :on-click #(dispatch-n [:user/update (:user/id @user) :user/tz client-tz]
                                                [:user/save (:user/id @user)])}
         "Autodetect"])]]))

(defn download []
  (let [pings (subscribe [:raw-pings])
        blob  (js/Blob. #js [(js/JSON.stringify (clj->js @pings))] #js {:type "text/json"})
        url   (.createObjectURL js/window.URL blob)]
    [:div
     [:h1 "Download your data"]
     [:a {:download "tictag.json"
          :href url}
      [:button {:type :button}
       [:i.fa.fa-fw.fa-download]
       "Download"]]]))

(defn macro-editor [id]
  (let [macro                                                        (subscribe [:macro id])
        {pending-expands-from :pending-macro/expands-from
         pending-expands-to   :pending-macro/expands-to
         :keys                [macro/expands-from macro/expands-to]} @macro]
    [:div
     [:div.input-group
      [input {:type            :text
              :placeholder     "expands from"
              :v               (or pending-expands-from expands-from "")
              :on-enter        #(dispatch [:macro/save id])
              :on-save         #(dispatch [:macro/update id :macro/expands-from %])}]
      [input {:type            :text
              :placeholder     "expands to"
              :v               (or pending-expands-to expands-to "")
              :on-enter        #(dispatch [:macro/save id])
              :on-save         #(dispatch [:macro/update id :macro/expands-to %])}] 
      [:button.button-success
       {:type     :button
        :on-click #(dispatch [:macro/save id])}
       "Save"]
      (when (not= id :temp)
        [:button.button-light
         {:type     :button
          :on-click #(dispatch [:macro/delete id])}
         "Delete"])]]))

(defn macros []
  (let [macros (subscribe [:macros])]
    [:div
     [:h1 "Macroexpansions"]
     [:p
      "Macroexpansions allow you to type " [:code "foo"] " and tag your ping with "
      "something like " [:code "bar"] [:code "baz"] "."]
     [:p
      "For example - imagine I tag my time as "
      [:code "a"] [:code "b"] [:code "c"] [:code "eat"]
      " very often, because I'm usually eating with person A, B, "
      "and C. I might create a macroexpansion from " [:code "fameat"] " to "
      [:code "a"] [:code "b"] [:code "c"] [:code "eat"]
      " to make typing this easier."]
     [:p "Another example might be if "
      "a tag is *always* a 'subtag'. Maybe " [:code "costco"] " should always expand "
      "to " [:code "costco"] [:code "grocery"] ", for example."]
     (for [id @macros]
       ^{:key id}
       [macro-editor id])
     [macro-editor :temp]]))

(defn settings []
  [:div {:style {:width "70%"
                 :margin :auto
                 :padding "3em 0"}}
   [slack]
   [:hr]
   [beeminder]
   [:hr]
   [macros]
   [:hr]
   [timezone]
   [:hr]
   [tagtime-import]
   [download]])


