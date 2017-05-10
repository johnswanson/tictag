(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [tictag.utils :refer [descend]]
            [goog.net.cookies]
            [cljs.spec :as s]))

(def interceptors [(when ^boolean goog.DEBUG re-frame.core/debug)])

(defn authenticated-xhrio [m token]
  (merge m {:headers {"Authorization" token}}))

(reg-event-fx
 :login/submit-login
 [interceptors]
 (fn [{:keys [db]} [_ params]]
   ;; TODO edit DB to say we're pending login and add UI
   {:http-xhrio {:method          :post
                 :uri             "/token"
                 :params          params
                 :timeout         8000
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:login/successful]
                 :on-failure      [:login/failed]}}))

(defn allowed-timezones [db]
  (let [tzs (:allowed-timezones db)]
    (set (map :name tzs))))

(reg-event-fx
 :login/submit-signup
 [interceptors]
 (fn [{:keys [db]} [_ params]]
   (let [[errs? params] (schemas/validate params
                                          (schemas/+new-user-schema+
                                           (allowed-timezones db)))]
     (if-not errs?
       {:http-xhrio {:method          :post
                     :uri             "/signup"
                     :params          params
                     :timeout         8000
                     :format          (transit-request-format {})
                     :response-format (transit-response-format {})
                     :on-success      [:login/successful]
                     :on-failure      [:login/failed]}}
       {:db (assoc-in db [:signup :errors] errs?)}))))

(reg-event-fx
 :login/successful
 [interceptors]
 (fn [{:keys [db]} [_ result]]
   {:db             (-> db
                        (assoc :auth-token (:token result))
                        (dissoc :signup))
    :dispatch-n [[:fetch-user-info]]
    :pushy-navigate :dashboard
    :set-cookie     {:auth-token (:token result)}}))

(reg-event-db
 :login/failed
 [interceptors]
 (fn [db [_ result]]
   ; TODO add handling of failure here
   db))


(reg-event-fx
 :fetch-pings
 [interceptors]
 (fn [{:keys [db]} _]
   {:db         (assoc db :fetching true)
    :http-xhrio (authenticated-xhrio
                 {:method          :get
                  :uri             "/pings"
                  :timeout         8000
                  :response-format (transit-response-format {})
                  :on-success      [:good-pings-result]
                  :on-failure      [:bad-pings-result]}
                 (:auth-token db))}))

(reg-event-fx
 :fetch-user-info
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method :get
                  :uri "/api/user/me"
                  :response-format (transit-response-format {})
                  :on-success [:user-me-success]
                  :on-failure [:user-me-failure]}
                 (:auth-token db))}))

(defn ref-to-goal [goal]
  [:goal/by-id (:goal/id goal)])

(defn ref-to-beeminder [beeminder]
  [:beeminder/by-id (:id beeminder)])

(defn ref-to-slack [slack]
  [:slack/by-id (:id slack)])

(defn ref-to-user [user]
  [:user/by-id (:id user)])

(defn normalize-beeminder [user beeminder]
  (-> beeminder
      (update :goals #(map ref-to-goal %))
      (update :goals vec)
      (assoc :user (ref-to-user user))))

(defn normalize-slack [user slack]
  (-> slack
      (assoc :user (ref-to-user user))))

(defn normalize-user [user]
  (-> user
      (update :beeminder ref-to-beeminder)
      (update :slack ref-to-slack)))

(defn normalize-goals [user beeminder goals]
  (into {} (for [g goals]
             [(:goal/id g) (-> g
                               (assoc :user (ref-to-user user))
                               (assoc :beeminder (ref-to-beeminder beeminder)))])))

(defn normalize-user-to-db [user]
  (let [slack     (:slack user)
        beeminder (:beeminder user)]
    {:slack/by-id           (when slack {(:id slack) (normalize-slack user slack)})
     :beeminder/by-id       (when beeminder {(:id beeminder) (normalize-beeminder user beeminder)})
     :user/by-id            (when user {(:id user) (normalize-user user)})
     :goal/by-id            (normalize-goals user beeminder (get-in user [:beeminder :goals]))
     :db/authenticated-user (ref-to-user user)}))

(reg-event-db
 :user-me-success
 [interceptors]
 (fn [db [_ user]]
   (merge db (normalize-user-to-db user))))

(reg-event-db
 :user-me-failure
 [interceptors]
 (fn [db _]
   ;; TODO
   db))

(reg-event-db
 :good-pings-result
 [interceptors]
 (fn [db [_ result]]
   (assoc db :pings result)))

(reg-event-db
 :bad-pings-result
 [interceptors]
 (fn [db [_ result]]
   (timbre/errorf "BAD RESULT: %s" (pr-str (keys result)))))

(reg-event-db
 :update-ping-query
 [interceptors]
 (fn [db [_ v]]
   (if (seq v)
     (assoc db :ping-query v)
     (assoc db :ping-query nil))))

(reg-fx
 :pushy-init
 (fn [_]
   (nav/start!)))

(reg-event-fx
 :beeminder-token/add
 [interceptors]
 (fn [{:keys [db]} [_ path val]]
   {:http-xhrio (authenticated-xhrio
                 {:method          :post
                  :uri             "/api/user/me/beeminder"
                  :params          {:token val}
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:beeminder-token/add-succeed]
                  :on-failure      [:beeminder-token/add-fail]}
                 (:auth-token db))
    :db (assoc-in db (conj path :token) val)}))

(reg-event-db
 :beeminder-token/add-fail
 [interceptors]
 (fn [db [_ val]]
   (-> db
       (assoc-in (conj (:db/authenticated-user db) :beeminder) nil))))

(reg-event-db
 :beeminder-token/add-succeed
 [interceptors]
 (fn [db [_ val]]
   (let [{:keys [id]} val]
     (let [user (descend db [:db/authenticated-user])]
       (-> db
           (assoc-in [:beeminder/by-id id] val)
           (assoc-in (conj (:db/authenticated-user db) :beeminder)
                     [:beeminder/by-id id]))))))

(reg-event-fx
 :delete-slack
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method          :delete
                  :uri             "/api/user/me/slack"
                  :timeout         8000
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:slack/delete-succeed]
                  :on-failure      [:slack/delete-fail]}
                 (:auth-token db))
    :db (update db :db/authenticated-user dissoc :slack)}))

(reg-event-fx
 :beeminder-token/delete
 [interceptors]
 (fn [{:keys [db]} [_ path]]
   {:http-xhrio (authenticated-xhrio
                 {:method          :delete
                  :uri             "/api/user/me/beeminder"
                  :timeout         8000
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:beeminder-token/delete-succeed]
                  :on-failure      [:beeminder-token/delete-fail path]}
                 (:auth-token db))
    :db (assoc-in db (conj (:db/authenticated-user db) :beeminder) nil)}))

(reg-event-db
 :beeminder-token/delete-fail
 [interceptors]
 (fn [db [_ path val]]
   (assoc-in db (conj (:db/authenticated-user db) :beeminder) path)))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v))))

(reg-fx
 :delete-cookie
 (fn [k]
   (.remove goog.net.cookies (name k))))

(reg-cofx
 :cookie
 (fn [coeffects key]
   (assoc-in coeffects
             [:cookies key]
             (.get goog.net.cookies (name key)))))

(reg-event-fx
 :initialize
 [(inject-cofx :cookie :auth-token) interceptors]
 (fn [{:keys [cookies]} _]
   (merge {:pushy-init true
           :http-xhrio {:method          :get
                        :uri             "/api/timezones"
                        :timeout         8000
                        :format          (transit-request-format {})
                        :response-format (transit-response-format {})
                        :on-success      [:success-timezones]
                        :on-failure      [:failed-timezones]}
           :dispatch-n [[:fetch-user-info]]
           :db {:auth-token (:auth-token cookies)}})))


(reg-event-db
 :success-timezones
 [interceptors]
 (fn [db [_ timezones]]
   (assoc db :allowed-timezones timezones)))

(def logging-out
  {:pushy-replace-token! :login
   :db {:auth-token nil :db/authenticated-user nil}
   :delete-cookie :auth-token})

(def not-logged-in-but-at-auth-page
  {:pushy-replace-token! :login})

(def logged-in-and-at-slack-callback
  {:pushy-replace-token! :dashboard})

(reg-event-fx
 :set-current-page
 [interceptors]
 (fn [{:keys [db]} [_ match]]
   (merge-with
    merge
    {:db (assoc db :nav match)}
    (when (and (= (:handler match) :slack-callback) (:auth-token db))
      logged-in-and-at-slack-callback)
    (when (and (= (:handler match) :dashboard) (not (:auth-token db)))
      not-logged-in-but-at-auth-page)
    (when (and (= (:handler match) :settings) (not (:auth-token db)))
      not-logged-in-but-at-auth-page)
    (when (= (:handler match) :logout)
      logging-out))))


(reg-fx
 :pushy-replace-token!
 (fn [route]
   (nav/replace-token! route)))

(reg-fx
 :pushy-navigate
 (fn [route]
   (nav/set-token! route)))

(reg-event-fx
 :redirect-to-page
 [interceptors]
 (fn [_ [_ page]]
   {:pushy-replace-token! page}))

(reg-event-db
 :goal/new
 [interceptors]
 (fn [db _]
   (let [user      (:db/authenticated-user db)
         beeminder (:beeminder (get-in db user))]
     (assoc-in db [:goal/by-id :temp] {:user user :beeminder beeminder :goal/id :temp}))))

(reg-event-db
 :goal/edit
 [interceptors]
 (fn [db [_ path new]]
   (assoc-in db path new)))

(reg-event-fx
 :goal/delete
 [interceptors]
 (fn [{:keys [db]} [_ path]]
   (let [goal           (get-in db path {})
         beeminder-path (:beeminder goal)
         beeminder      (get-in db beeminder-path {})
         id             (:goal/id goal)]
     (if (= :temp id)
       {:db (assoc-in db path nil)}
       {:http-xhrio (authenticated-xhrio
                     {:uri             (str "/api/user/me/goals/" id)
                      :method          :delete
                      :timeout         8000
                      :format          (transit-request-format {})
                      :response-format (transit-response-format {})
                      :on-success      [:good-goal-delete-result (:goal/id goal)]
                      :on-failure      [:bad-goal-delete-result (:goal/id goal)]}
                     (:auth-token db))
        :db         (-> db
                        (assoc-in path nil)
                        (update-in (conj beeminder-path :goals)
                                   (fn [goals]
                                     (remove #(= path %) goals))))}))))

(reg-event-fx
 :goal/save
 [interceptors]
 (fn [{:keys [db]} [_ path]]
   (let [goal (get-in db path {})
         base (if (= :temp (:goal/id goal))
                {:method :post
                 :uri "/api/user/me/goals/"}
                {:method :put
                 :uri (str "/api/user/me/goals/" (:goal/id goal))})]
     (if (s/valid? :tictag.schemas/goal goal)
       {:http-xhrio (authenticated-xhrio
                     (merge {:params          goal
                             :timeout         8000
                             :format          (transit-request-format {})
                             :response-format (transit-response-format {})
                             :on-success      [:good-goal-update-result (:goal/id goal)]
                             :on-failure      [:bad-goal-update-result (:goal/id goal)]}
                            base)
                     (:auth-token db))}
       {}))))

(reg-event-db
 :good-goal-update-result
 [interceptors]
 (fn [db [_ old-id to-merge]]
   (js/console.log old-id to-merge)
   (if (= old-id :temp)
     (-> db
         ;; three parts:
         ;; - add the new goal (by id) to my beeminder goals
         ;; - add the new goal to the {:goal-id goal} map
         ;; - remove the old :temp goal
         (update-in (conj (:beeminder to-merge) :goals)
                    conj [:goal/by-id (:goal/id to-merge)])
         (assoc-in [:goal/by-id :temp] nil)
         (assoc-in [:goal/by-id (:goal/id to-merge)] to-merge))
     (assoc-in db [:goal/by-id (:goal/id to-merge)] to-merge))))
