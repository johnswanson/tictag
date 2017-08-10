(ns tictag.views.login
  (:require [re-com.core :as re-com]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]))

(defn login []
  (let [user (subscribe [:pending-user])
        errs (subscribe [:login-errors])
        submit          #(do
                           (dispatch [:token/create])
                           (.preventDefault %))]
    (fn []
      [re-com/box
       :child
       [:form {:on-submit submit}
        [re-com/v-box
         :width "400px"
         :justify :center
         :children [[:label "Username"
                     [re-com/input-text
                      :style {:border-radius "0px"}
                      :width "100%"
                      :placeholder "Username"
                      :model (or (:pending-user/username @user) "")
                      :on-change #(dispatch [:user/update :temp :username %])]]
                    [:label "Password"
                     [re-com/input-text
                      :style {:border-radius "0px"}
                      :width "100%"
                      :placeholder "Password"
                      :model (or (:pending-user/pass @user) "")
                      :on-change #(dispatch [:user/update :temp :pass %])]]
                    (when @errs
                      [re-com/alert-box
                       :alert-type :danger
                       :heading "Unauthorized!"])
                    [re-com/gap :size "10px"]
                    [:input {:type  :submit
                             :style {:display :none}}]
                    [re-com/button
                     :on-click submit
                     :style {:width         "100%"
                             :font-size     "22px"
                             :font-weight   "300"
                             :border        "1px solid black"
                             :border-radius "0px"
                             :padding       "20px 26px"}
                     :label "Log In!"]]]]])))




