(ns tictag.views.signup
  (:require [re-com.core :as re-com]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [goog.string :as gstr]))

(defn input [temp errs]
  [re-com/input-text
   :model temp
   :on-change #(reset! temp %)
   :status (when @errs :error)])

(defn input-password [temp errs]
  [re-com/input-password
   :model temp
   :on-change #(reset! temp %)
   :status (when @errs :error)])

(defn input-timezone [temp errs allowed-timezones]
  [:div
   [re-com/single-dropdown
    :width "450px"
    :choices (map (fn [tz] {:id tz :label tz}) @allowed-timezones)
    :model temp
    :filter-box? true
    :on-change #(reset! temp %)]])

(defn signup []
  (let [username          (reagent/atom "")
        username-errors   (subscribe [:login-errors :username])
        password          (reagent/atom "")
        password-errors   (subscribe [:login-errors :password])
        email             (reagent/atom "")
        email-errors      (subscribe [:login-errors :email])
        timezone          (reagent/atom "America/Los_Angeles")
        timezone-errors   (subscribe [:login-errors :timezone])
        allowed-timezones (subscribe [:allowed-timezones])]
    (fn []
      [re-com/v-box
       :children [[:label "Username"
                   [input username username-errors]]
                  [:label "Password"
                   [input-password password password-errors]]
                  [:label "Email"
                   [input email email-errors]]
                  [:label "Timezone"
                   [input-timezone timezone timezone-errors allowed-timezones]]
                  [:button
                   {:on-click #(dispatch [:login/submit-signup
                                          {:username @username
                                           :password @password
                                           :email    @email
                                           :tz       @timezone}])}
                   "Sign Up"]]])))



