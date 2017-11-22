(ns tictag.views.query.filters
  (:require [re-frame.core :refer [subscribe dispatch]]))

(defn query []
  (let [v (subscribe [:pie-filters/query])]
    (fn []
      [:div.input-field
       [:label "Query"]
       [:input {:type :text
                :value @v
                :on-change #(dispatch [:pie-filters/change-query (-> % .-target .-value)])}]
       [:p.input-hint "Include only pings matching " [:code "query"] " for consideration, for example " [:code (pr-str '(not sleep))] " to look only at waking hours"]])))

(defn date-start []
  (let [start-date (subscribe [:pie-filters/start-date])]
    (fn []
      [:div.input-field
       [:label "Start Date"]
       [:div.input-group
        [:input {:type :date
                 :value @start-date
                 :on-change #(js/console.log (-> % .-target .-value))}]]])))

(defn date-end []
  (let [date (subscribe [:pie-filters/end-date])]
    (fn []
      [:div.input-field
       [:label "End Date"]
       [:div.input-group
        [:input {:type :date
                 :value @date
                 :on-change #(js/console.log (-> % .-target .-value))}]]])))


(defn date-range []
  [:fieldset
   [:legend "Date Range"]
   [:div.input-group
    [:button.button-xs {:type :button
                        :on-click #(dispatch [:pie-filters/select-today])}
     "Today"]
    [:button.button-xs {:type :button
                        :on-click #(dispatch [:pie-filters/select-last-week])}
     "Last Week"]
    [:button.button-xs {:type :button
                        :on-click #(dispatch [:pie-filters/select-last-month])}
     "Last Month"]
    [:button.button-xs {:type :button
                        :on-click #(dispatch [:pie-filters/select-last-year])}
     "Last Year"]
    [:button.button-xs {:type :button
                        :on-click #(dispatch [:pie-filters/select-all-dates])}
     "All"]]
   [date-start]
   [date-end]])

(def days-of-week
  '(Sun Mon Tue Wed Thu Fri Sat))

(def weekdays #{:mon :tue :wed :thu :fri})
(def weekends #{:sun :sat})

(defn all-days-selected? [days] (= days (clojure.set/union weekdays weekends)))
(defn weekdays-only? [days] (= days weekdays))
(defn weekends-only? [days] (= days weekends))

(defn day-button [{:keys [text on-change value]}]
  [:button.button-xs {:type :button
                      :className (when value "button-success")
                      :on-click #(on-change (not value))}
   text])

(defn day-of-week []
  (let [days           (subscribe [:pie-filters/days])
        all-days?      (subscribe [:pie-filters/all-days?])
        no-days?       (subscribe [:pie-filters/no-days?])
        weekdays-only? (subscribe [:pie-filters/weekdays-only?])
        weekends-only? (subscribe [:pie-filters/weekends-only?])]
    (fn []
      [:fieldset
       [:legend "Days of Week"]
       [:div.input-group
        [day-button
         {:text "All"
          :on-change #(dispatch [:pie-filters/day-select-all])
          :value @all-days?}]
        [day-button
         {:text "None"
          :on-change #(dispatch [:pie-filters/day-select-none])
          :value @no-days?}]
        [day-button
         {:text "Weekdays"
          :on-change #(dispatch [:pie-filters/day-select-weekdays-only])
          :value @weekdays-only?}]
        [day-button
         {:text "Weekends"
          :on-change #(dispatch [:pie-filters/day-select-weekends-only])
          :value @weekends-only?}]]
       (for [{:keys [key name selected?]} @days]
         ^{:key name}
         [:label {:style {:cursor :pointer
                          :font-weight (if selected? "bold" "normal")
                          :font-size "0.75em"}
                  :on-click #(dispatch [:pie-filters/day-select key (not selected?)])}
          name])])))

(defn filters []
  [:div
   [:h2 "Filters"]
   [query]
   [date-range]
   [day-of-week]])

