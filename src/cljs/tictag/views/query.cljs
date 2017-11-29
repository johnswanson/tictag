(ns tictag.views.query
  (:require [re-frame.core :refer [subscribe dispatch]]
            [tictag.views.common :as common]
            [tictag.views.query.filters :refer [filters]]
            [reagent.core :as reagent]
            [tictag.constants :refer [ENTER]]
            [taoensso.timbre :as timbre]
            [goog.string :as gstring]
            [clojure.string :as str]
            [c2.scale]))

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

(defn arc-path [{:keys [x y r] :as m} pct0 pct1 {:keys [reverse?]}]
  (let [[x0 y0]           (-> (if reverse? pct1 pct0)
                              pct-to-angle
                              angle-to-cart
                              (scale m)
                              (invert-y m)
                              (move-to-origin m))
        [x1 y1]           (-> (if reverse? pct0 pct1)
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
      (if reverse? 0 1)

      ;; x y
      x1 y1])))

(defn arc-path+origin [{x :x y :y :as m} pct0 pct1]
  (str (arc-path m pct0 pct1 {}) " L " x " " y))

(defn pie-slice [{:keys [x y r] :as m} {:keys [name start end color]}]
  (let [id (gensym "slice")
        over? (reagent/atom false)]
    (fn [{:keys [x y r] :as m} {:keys [name start end color hours hours-per-day] :as p}]
      (when (> (- end start) 0)
        [:g
         [:path {:d      (arc-path
                          (update m :r #(* % (if @over? 1.1 1.04)))
                          (- start 0.2)
                          (+ end 0.2)
                          {:reverse? (> 0.75 (+ start (/ (- end start) 2)) 0.25)})
                 :id     id
                 :fill   :none
                 :stroke :none}]
         [:path {:d    (arc-path+origin
                        (if @over? (update m :r #(* 1.02 %)) m)
                        start
                        end)
                 :on-mouse-over #(reset! over? true)
                 :on-mouse-leave #(reset! over? false)
                 :fill color}]
         [:text
          [:textPath
           {:xlink-href   (str "#" id)
            :start-offset "50%"
            :style        {:text-anchor :middle
                           :font-family "Roboto"
                           :font-size   (if @over? "1rem" "0.75rem")
                           :font-weight 300}}
           (if @over?
             (str "(" hours-per-day " / day)")
             name)]]]))))

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
     (when (:name slice)
       [pie-slice m (assoc slice :color (nth colors i))]))])

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

(defn query-sparkline* [freqs day-limits]
  (let [[min-day max-day] day-limits
        xscale            (c2.scale/linear :domain [min-day max-day]
                                           :range [0 80])
        yscale            (c2.scale/linear :domain [0 (apply max (vals freqs))]
                                           :range [16 0])]
    [:span {:style {:padding "0 0.5rem" :float :right}}
     [:svg {:height "16px" :width "160px"}
      [:g {:fill :none :stroke "#111"}
       (c2.svg/line
        (map
         (fn [[d freq]]
           [(xscale d) (yscale freq)])
         freqs))]]]))

(defn query-sparkline [query]
  (let [freqs      (subscribe [:pie/daily-totals query])
        day-limits (subscribe [:pie/daily-limits])]
    [query-sparkline* @freqs @day-limits]))

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
          [:span {:on-click #(reset! editing? true)} query])]
       [query-sparkline query]])))

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

(defn query-hint []
  (let [vs (subscribe [:pie/others])]
    (fn []
      (when (seq @vs)
        [:div.input-hint
         "Suggestions: " (str/join ", " @vs)]))))

(defn queries []
  [:div {:style {:margin "2rem 0"}}
   [:h2 "Queries"]
   [query-list]
   [:div.input-field
    [input {:query ""
            :on-save #(dispatch [:pie/add-slice %])}]
    [query-hint]]])


(defn tag-table-row-view [tag count tag-% minutes active? time-per-day]
  [:tr (if active?
         {:style {:background-color "#333"
                  :color            "#ddd"}}
         {:style {:background-color "#ddd"
                  :color            "#333"}})
   [:td tag]
   [:td count]
   [:td (gstring/format "%.1f%%" tag-%)]
   [:td time-per-day]])



(defn tag-table-row [tag]
  (let [count        (subscribe [:tag-count tag])
        tag-%        (subscribe [:tag-% tag])
        minutes      (subscribe [:minutes-for-tag tag])
        active?      (subscribe [:tag-active? tag])
        time-per-day (subscribe [:time-per-day-for-tag tag])]
    [tag-table-row-view tag @count @tag-% @minutes @active? @time-per-day]))

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
                  :overflow-y :scroll}}
    [:div {:style {:padding "3rem"
                   :background-color "white"}}
     [:div
      [:svg {:viewBox "0 0 800 800" :style {:display :block :margin :auto}}
       [pie-chart* {:x 400 :y 400 :r 350}]
       [:circle {:cx 400 :cy 400 :r 300 :fill "white"}]]]]]])
