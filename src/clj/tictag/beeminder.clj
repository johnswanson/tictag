(ns tictag.beeminder
  (:require [org.httpkit.client :as http]
            [cheshire.core :as cheshire]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.data :refer [diff]]
            [clj-time.core :as t]
            [clj-time.periodic :as p]
            [tictag.db :as db]
            [tictag.beeminder-matching :refer [match?]]
            [tictag.utils :as utils]))

(timbre/refer-timbre)

(defn goal-url [user goal]
  (format "https://www.beeminder.com/api/v1/users/%s/goals/%s.json" user goal))

(defn datapoints-url [user goal]
  (format "https://www.beeminder.com/api/v1/users/%s/goals/%s/datapoints.json" user goal))

(defn datapoints [auth-token user goal]
  (:datapoints
   (cheshire/parse-string
    (:body
     @(http/request {:url         (goal-url user goal)
                     :method      :get
                     :query-params {:auth_token auth-token
                                    :datapoints true}}))
    true)))

(defn update-datapoint! [auth-token user goal datapoint]
  (http/request {:url          (format "https://www.beeminder.com/api/v1/users/%s/goals/%s/datapoints/%s.json"
                                       user goal (:id datapoint))
                 :method       :put
                 :query-params {:auth_token auth-token
                                :value      (:value datapoint)}}))

(defn create-datapoint! [auth-token user goal datapoint]
  (http/request {:url (format "https://www.beeminder.com/api/v1/users/%s/goals/%s/datapoints.json"
                              user goal)
                 :method :post
                 :query-params {:auth_token auth-token
                                :value (:value datapoint)
                                :daystamp (:daystamp datapoint)}}))

(defn save-datapoint! [auth-token user goal datapoint]
  (if (:id datapoint)
    (update-datapoint! auth-token user goal datapoint)
    (create-datapoint! auth-token user goal datapoint)))

(defn delete-datapoint! [auth-token user goal datapoint]
  (when (:id datapoint)
    (http/request {:url (format "https://www.beeminder.com/api/v1/users/%s/goals/%s/datapoints/%s.json"
                                user goal (:id datapoint))
                   :method :delete
                   :query-params {:auth_token auth-token}})))

(defn past-week-days []
  (set (map db/local-day (take 7 (p/periodic-seq (t/now) (t/days -1))))))

(defn days-matching-tag [tags rows]
  (->> rows
       (filter #(match? tags (:tags %)))
       (map :local-day)
       (frequencies)))

(defn sync! [{:keys [db tagtime]} user]
  (debugf "Beginning beeminder sync: %s" (:enabled? (:beeminder user)))
  (when (:enabled? (:beeminder user))
    (when-let [goals (seq (db/get-goals db (:beeminder user)))]
      (tracef "goals are %s, getting rows" goals)
      (let [in-past-week? (past-week-days)
            rows          (filter #(in-past-week? (:local-day %))
                                  (db/get-pings-by-user (:db db) user))]
        (doseq [{:keys [goal/name goal/tags]} goals]
          (debugf "Syncing goal: %s with tags %s" name tags)
          (let [{:keys [username token]} (:beeminder user)
                days                     (days-matching-tag tags rows)
                existing-datapoints      (filter
                                          #(in-past-week? (:daystamp %))
                                          (datapoints
                                           token
                                           username
                                           name))
                existing-map             (group-by :daystamp existing-datapoints)
                to-save                  (filter :value
                                                 (for [[daystamp value] days
                                                       :let             [hours (* (/ (:gap tagtime) 60 60) value)
                                                                         {id :id old-value :value}
                                                                         (first
                                                                          (existing-map daystamp))]]
                                                   {:id       id
                                                    :daystamp daystamp
                                                    :value    (when (or (not old-value)
                                                                        (not= (float old-value) (float hours)))
                                                                (float hours))}))
                to-delete                (concat
                                          (remove (fn [{:keys [daystamp]}]
                                                    (days daystamp))
                                                  existing-datapoints)
                                          (flatten
                                           (remove nil?
                                                   (map rest (vals existing-map)))))
                save-futures             (doall (map #(save-datapoint! token username name %) to-save))
                delete-futures           (doall (map #(delete-datapoint! token username name %) to-delete))]
            (doseq [resp (concat save-futures delete-futures)]
              (if-not (utils/success? @resp)
                (timbre/error @resp)
                (timbre/trace (get-in @resp [:opts :method]) (:status @resp))))))))))

(defn user-for [token]
  (let [resp (-> (http/request {:url         "https://www.beeminder.com/api/v1/users/me.json"
                                :method      :get
                                :query-params {:auth_token token}})
                 (deref))]
    (if (= (:status resp) 200)
      (cheshire/parse-string (:body resp) true)
      nil)))
