(ns tictag.views.query
  (:require [re-frame.core :refer [subscribe dispatch]]
            [tictag.views.common :as common]
            [tictag.views.query.filters :refer [filters]]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]))

(defn pct-to-angle [pct]
  (- (* 2 Math/PI) (* 2 Math/PI pct)))

(defn angle-to-cart [angle]
  (let [a (+ angle (/ Math/PI 2))]
    [(Math/cos a) (Math/sin a)]))

(defn scale [[x0 y0] {:keys [x y r]}]
  [(* (inc x0) r)
   (* (inc y0) r)])

(defn invert-y [[x0 y0] {:keys [x y r]}]
  [x0 (- (* 2 r) y0)])

(defn move-to-origin [[x0 y0] {:keys [x y r]}]
  [(+ x0 (- x r))
   (+ y0 (- y r))])

(defn polar-to-cart [{:keys [width height]} [r theta]]
  [(* r (Math/cos theta))
   (* r (Math/sin theta))])

(defn arc-path [{:keys [x y r] :as m} pct0 pct1]
  (let [[x0 y0]           (-> pct0
                              pct-to-angle
                              angle-to-cart
                              (scale m)
                              (invert-y m)
                              (move-to-origin m))
        [x1 y1]           (-> pct1
                              pct-to-angle
                              angle-to-cart
                              (scale m)
                              (invert-y m)
                              (move-to-origin m))]
    (str/join
     " "
     ["M" x0 y0
      "A"
      ;; rx ry
      r r
      ;; x-axis-rotation
      0
      ;; large-arc-flag
      (if (< 0.5 (- pct1 pct0)) 1 0)

      ;; sweep-flag, always go clockwise
      1

      ;; x y
      x1 y1])))

(defn arc-path+origin [{x :x y :y :as m} pct0 pct1]
  (str (arc-path m pct0 pct1) " L " x " " y))

(defn pie-slice [{:keys [x y r] :as m} {:keys [name start end color]}]
  (let [id (gensym "slice")]
    [:g
     [:path {:d (arc-path (update m :r #(* 1.05 %)) start end)
             :id id
             :fill :none
             :stroke :none}]
     [:path {:d    (arc-path+origin m start end)
             :fill color}]
     [:text
      [:textPath
       {:xlink-href (str "#" id)
        :start-offset "50%"
        :style {:text-anchor :middle :font-size "0.75rem"}}
       name]]]))

(def colors (cycle ["Tomato"
                    "Turquoise"
                    "Coral"
                    "CornflowerBlue"
                    "DeepPink"
                    "LightSlateGray"]))

(defn pie-chart [{:keys [x y r opts] :as m} slices]
  [:g
   (for [[i slice] (map-indexed vector slices)]
     ^{:key i}
     [pie-slice m (assoc slice :color (nth colors i))])])

(defn pie-chart* [{:keys [x y r opts] :as m}]
  (let [sub (subscribe [:pie/result])]
    (fn []
      [pie-chart m @sub])))


(defn input [{:keys [query on-save on-stop]}]
  (let [val  (reagent/atom query)
        stop #(do (reset! val "")
                  (when on-stop (on-stop)))
        save #(let [v (-> @val str str/trim)]
                (when (seq v) (on-save v))
                (stop))]
    (fn [props]
      [:input (merge props
                     {:type        "text"
                      :value       @val
                      :auto-focus  true
                      :on-blur     save
                      :on-change   #(reset! val (-> % .-target .-value))
                      :on-key-down #(case (.-which %)
                                      13 (save)
                                      27 (stop)
                                      nil)})])))

(defn query-item []
  (let [editing? (reagent/atom false)]
    (fn [{:keys [id query]}]
      [:div
       [:i.fa.fa-fw.fa-times {:style {:color :grey :cursor :pointer}
                              :on-click #(dispatch [:pie/delete-slice id])}]
       [:div {:style {:display :inline-block}}
        (if @editing?
          [input
           {:query query
            :on-save #(dispatch [:pie/update-slice id %])
            :on-stop #(reset! editing? false)}]
          [:span {:on-click #(reset! editing? true)} query])]])))

(defn query-list []
  (let [queries (subscribe [:pie/indexed-slices])]
    [:div
     (for [q @queries]
       ^{:key (:id q)} [query-item q])]))

(defn piechart []
  (let [sub  (subscribe [:pie/result])]
    (let [total (reduce + (map second @sub))]
      [:div {:style {:width "300px" :height "50vh"}}
       (for [[i [name n]] (map-indexed vector @sub)]
         ^{:key i} [:div {:style {:width "300px"
                                  :height (str (* 100 (/ n total)) "%")
                                  :background-color (nth colors i)}}
                       name])])))

(defn queries []
  [:div {:style {:margin "2rem 0"}}
   [:h2 "Queries"]
   [query-list]
   [input {:query ""
           :on-save #(dispatch [:pie/add-slice %])}]])

(defn query []
  [:div {:style {:height "100vh"
                 :width "100%"
                 :display "grid"
                 :grid-template-columns "450px 1fr"
                 :grid-template-rows "1fr"
                 :grid-template-areas "\"subsidebar chart\""}}
   [:div {:style {:grid-area "subsidebar"
                  :overflow-y :scroll}}
    [:div {:style {:width "90%"
                   :margin "2rem auto"}}
     [queries]
     [filters]]]
   [:div {:style {:grid-area "chart"
                  :overflow-y :scroll
                  :background-color common/white}}
    [:div {:style {:padding "3rem"
                   :margin "3rem"
                   :background-color "white"}}
     [:h1 "Pie Chart"]
     [:div
      [:svg {:width 600 :height 600 :style {:display :block :margin :auto}}
       [pie-chart* {:x 300 :y 300 :r 250}]
       [:circle {:cx 300 :cy 300 :r 225 :fill "white"}]]]]]])

