(ns tictag.beeminder
  (:require [org.httpkit.client :as http]
            [cheshire.core :as cheshire]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.data :refer [diff]]
            [tictag.db :as db]))

(defmulti match? (fn [a _] (class a)))

(defmethod match? java.util.regex.Pattern
  [a b]
  (some #(re-find a %) b))

(defmethod match? java.lang.String
  [a b]
  (b a))

(defmethod match? clojure.lang.Keyword
  [a b]
  (b (name a)))

(defmethod match? clojure.lang.PersistentList
  [a b]
  (match? (vec a) b))

(defmethod match? clojure.lang.PersistentVector
  [[pred & args] b]
  (case (name pred)
    "and" (every? #(match? % b) args)
    "or"  (some #(match? % b) args)))

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
  (http/request {:url (format "https://www.beeminder.com/api/v1/users/%s/goals/%s/datapoints/%s.json"
                              user goal (:id datapoint))
                 :method :put
                 :query-params {:auth_token auth-token
                                :value (:value datapoint)}}))

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

(defn days-matching-tag [tags rows]
  (timbre/tracef "Matching tags %s, rows %s" tags rows)
  (->> rows
       (filter #(match? tags (:tags %)))
       (map :local-day)
       (frequencies)))

(defn sync! [db user]
  (timbre/debugf "Beginning beeminder sync: %s" (:enabled? (:beeminder user)))
  (when (:enabled? (:beeminder user))
    (when-let [goals (seq (db/get-goals db (:beeminder user)))]
      (timbre/tracef "goals are %s, getting rows" goals)
      (let [rows (db/get-pings-by-user (:db db) user)]
        (timbre/tracef "Rows: %s" rows)
        (doseq [{:keys [goal/name goal/tags]} goals]
          (timbre/debugf "Syncing goal: %s with tags %s" name tags)
          (let [{:keys [username token]} (:beeminder user)
                days                     (days-matching-tag tags rows)
                existing-datapoints      (datapoints
                                          (get-in
                                           user
                                           [:beeminder :token])
                                          username
                                          name)
                existing-map             (group-by :daystamp existing-datapoints)
                to-save                  (filter :value
                                                 (for [[daystamp value] days
                                                       :let             [hours (* (/ (:gap (:tagtime db)) 60 60) value)
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
              (timbre/debugf "result %s %s: %s"
                             (-> @resp :opts :url)
                             (-> @resp :opts :method)
                             (:status @resp)))))))))

(defn user-for [token]
  (let [resp (-> (http/request {:url         "https://www.beeminder.com/api/v1/users/me.json"
                                :method      :get
                                :query-params {:auth_token token}})
                 (deref))]
    (if (= (:status resp) 200)
      (cheshire/parse-string (:body resp) true)
      nil)))
