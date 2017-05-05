(ns tictag.views.signup
  (:require [re-com.core :as re-com]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [goog.string :as gstr]
            [tictag.views.inputs :refer [input input-password input-timezone]]))


(defn signup []
  (let [username          (reagent/atom "")
        username-errors   (subscribe [:login-errors :username])
        password          (reagent/atom "")
        password-errors   (subscribe [:login-errors :password])
        email             (reagent/atom "")
        email-errors      (subscribe [:login-errors :email])
        timezone          (reagent/atom "America/Los_Angeles")
        allowed-timezones (subscribe [:allowed-timezones])
        submit            #(dispatch [:login/submit-signup
                                      {:username @username
                                       :password @password
                                       :email    @email
                                       :tz       @timezone}])]
    (fn []
      [re-com/box
       :child
       [:form {:on-submit #(do (submit) (.preventDefault %))}
        [re-com/v-box
         :width "400px"
         :justify :center
         :children [[:label "Username"
                     [input username username-errors "Username"]]
                    [:label "Password"
                     [input-password password password-errors]]
                    [:label "Email"
                     [input email email-errors "Email"]]
                    [:label "Timezone"
                     [input-timezone timezone allowed-timezones]]
                    [re-com/gap :size "10px"]
                    [re-com/button
                     :on-click #(dispatch [:login/submit-signup
                                           {:username @username
                                            :password @password
                                            :email    @email
                                            :tz       @timezone}])
                     :style {:width         "100%"
                             :font-size     "22px"
                             :font-weight   "300"
                             :border        "1px solid black"
                             :border-radius "0px"
                             :padding       "20px 26px"}
                     :label "Sign Up!"]]]]])))



