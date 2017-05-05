(ns tictag.views.login
  (:require [re-com.core :as re-com]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [tictag.views.inputs :refer [input input-password]]))

(defn login []
  (let [username          (reagent/atom "")
        username-errors   (subscribe [:login-errors :username])
        password          (reagent/atom "")
        password-errors   (subscribe [:login-errors :password])]
    (fn []
      [re-com/v-box
       :width "400px"
       :justify :center
       :children [[:label "Username"
                   [input username username-errors "Username"]]
                  [:label "Password"
                   [input-password password password-errors]]
                  [re-com/gap :size "10px"]
                  [re-com/button
                   :on-click #(dispatch [:login/submit-login
                                         {:username @username
                                          :password @password}])
                   :style {:width "100%"
                           :font-size "22px"
                           :font-weight "300"
                           :border "1px solid black"
                           :border-radius "0px"
                           :padding "20px 26px"}
                   :label "Log In!"]]])))




