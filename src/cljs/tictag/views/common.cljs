(ns tictag.views.common
  (:require [re-frame.core :refer [dispatch subscribe]]
            [tictag.nav :refer [route-for]]
            [goog.string :as str]
            [reagent.core :as reagent]
            [taoensso.timbre :as timbre]))

(defn input [{:keys [v]}]
  (let [ext-model (reagent/atom v)
        int-model (reagent/atom @ext-model)]
    (fn [{:keys [on-save on-stop on-enter change-on-blur v] :as props}]
      (when (not= @ext-model v)
        (reset! ext-model v)
        (reset! int-model v))
      (let [stop  #(when on-stop
                     (on-stop))
            save  #(do (on-save @int-model)
                       (stop))
            enter #(do (save) (when on-enter (on-enter)))]
        [:input (merge props
                       {:value       @int-model
                        :on-blur     save
                        :on-change   #(reset! int-model (-> % .-target .-value))
                        :on-key-down #(case (.-which %)
                                        13 (enter)
                                        27 (stop)
                                        nil)})]))))

(def colors
  {:white      "#ddd"
   :accent     "#f433ab"
   :black      "#070608"
   :dark-grey  "#2b303a"
   :grey       "#384c59"
   :light-grey "#808d95"})

(def white (:white colors))
(def accent (:accent colors))
(def black (:black colors))
(def dark-grey (:dark-grey colors))
(def grey (:grey colors))
(def light-grey (:light-grey colors))

(def icon
  {:about     [:i.fa.fa-fw.fa-question-circle-o]
   :dashboard [:i.fa.fa-fw.fa-pie-chart]
   :settings  [:i.fa.fa-fw.fa-cog]
   :editor    [:i.fa.fa-fw.fa-pencil]
   :logout    [:i.fa.fa-fw.fa-sign-out]
   :login     [:i.fa.fa-fw.fa-sign-in]
   :signup    [:i.fa.fa-fw.fa-user-plus]})

(defn link [route-name current-page]
  [:a {:href (route-for route-name)
       :style {:width "100%"
               :height "100%"
               :font-weight (if (= current-page route-name)
                              :bold
                              nil)
               :color (if (= current-page route-name)
                        white
                        grey)}}
   [:div
    [:span {:style {:margin-left "1em"}}]
    (icon route-name)
    [:span {:style {:margin-left "1em"}} (str/capitalize (name route-name))]]])

(defn logo []
  [:a {:href  "/"
       :style {:width  "100%"
               :height "100%"
               :color  accent}}
   [:div {:style {:width       "70%"
                  :margin      :auto
                  :margin-top  "1rem"
                  :text-align  :center
                  :font-size   "2rem"
                  :font-weight :bold}}
    [:h1 "TTC"]]])

(defn nav-for-user [user current-page]
  [:div
   [logo]
   [:div {:style {:flex "0 0 1rem"}}]
   (when-not user [link :login current-page])
   (when-not user [link :signup current-page])
   (when-not user [link :about current-page])
   (when user [link :dashboard current-page])
   (when user [link :settings current-page])
   (when user [link :editor current-page])
   (when user [link :logout current-page])])

(defn- nav []
  (let [user         (subscribe [:authorized-user])
        current-page (subscribe [:active-panel])]
    (fn []
      [nav-for-user @user @current-page])))

(defn page
  [& content]
  [:div
   {:style {:height                "100vh"
            :width                 "100%"
            :display               "grid"
            :grid-template-columns "200px 1fr"
            :grid-template-rows    "1fr"
            :grid-template-areas   "\"sidebar main\""}}
   [:div {:style {:grid-area        "sidebar"
                  :background-color dark-grey}}
    [nav]]
   [:div {:style {:grid-area  "main"
                  :overflow-y :scroll}}
    content]])
