(ns tictag.views.login
  (:require [re-frame.core :refer [dispatch subscribe]]
            [tictag.views.common :refer [input]]
            [reagent.core :as reagent]
            [taoensso.timbre :as timbre]))

(defn login []
  (let [user   (subscribe [:pending-user])
        errs   (subscribe [:login-errors])
        submit #(do (dispatch [:token/create])
                    (.preventDefault %))]
    (fn []
      [:div
       {:style {:width      "70%"
                :margin     :auto
                :margin-top "3em"}}
       [:h1 "Login"]
       (when @errs [:div.alert.alert-danger {:style {:text-align :center :font-weight :bold}} "Invalid username or password"])
       [:form {:on-submit submit}
        [:div.input-field
         [:label "Username/Email"]
         [input {:v       (:pending-user/username @user)
                 :type    :text
                 :on-save #(dispatch [:user/update :temp :username %])
                 :on-enter #(dispatch [:token/create])}]]
        [:div.input-field
         [:label "Password"]
         [input {:v       (or (:pending-user/pass @user) "")
                 :type    :password
                 :on-save #(dispatch [:user/update :temp :pass %])
                 :on-enter #(dispatch [:token/create])}]]
        [:button {:type :button
                  :on-click #(dispatch [:token/create])} "Log In"]]])))




