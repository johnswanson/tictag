(ns tictag.views.signup
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [goog.string :as gstr]
            [taoensso.timbre :as timbre]
            [tictag.views.common :refer [input]]
            [tictag.utils :as utils]))


(defn signup []
  (let [user              (subscribe [:pending-user])
        errors            (subscribe [:signup-errors])
        allowed-timezones (subscribe [:allowed-timezones])
        submit            #(do (dispatch [:user/save :temp])
                               (.preventDefault %))]
    (fn []
      [:div
       {:style {:width      "70%"
                :margin     :auto
                :margin-top "3em"}}
       [:h1 "Signup"]
       [:form {:on-submit submit}
        [:div.input-field {:className (when (:user/username @errors) "input-invalid")}
         [:label "Username"]
         [input {:v        (:pending-user/username @user)
                 :type     :text
                 :on-save  #(dispatch [:user/update :temp :username %])
                 :on-enter #(dispatch [:user/save :temp])}]]
        [:div.input-field {:className (when (:user/pass @errors) "input-invalid")}
         [:label "Password"]
         [input {:v        (or (:pending-user/pass @user) "")
                 :type     :password
                 :on-save  #(dispatch [:user/update :temp :pass %])
                 :on-enter #(dispatch [:user/save :temp])}]]

        [:div.input-field {:className (when (:user/email @errors) "input-invalid")}
         [:label "Email"]
         [input {:v        (:pending-user/email @user)
                 :type     :text
                 :on-save  #(dispatch [:user/update :temp :email %])
                 :on-enter #(dispatch [:user/save :temp])}]]
        [:div.input-field {:className (when (:user/tz @errors) "input-invalid")}
         [:label "Time Zone"]
         [:div.input-group
          [:select {:value     (:pending-user/tz @user)
                    :on-change #(dispatch [:user/update :temp :tz (-> % .-target .-value)])}
           (for [tz @allowed-timezones]
             ^{:key tz} [:option tz])]
          [:button.button {:type :button
                           :on-click #(dispatch [:user/update :temp :tz (utils/local-tz)])} "Autodetect"]]]
        [:button {:type     :button
                  :on-click #(dispatch [:user/save :temp])} "Sign Up"]]])))



