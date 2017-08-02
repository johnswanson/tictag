(ns tictag.resources.utils
  (:require [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]))


(defn id [ctx] (some-> ctx :request :route-params :id Integer.))

(defn uid [ctx] (some-> ctx :request :user-id))

(defn params [ctx]
  (get-in ctx [:request :params]))

(defn replace-key [e [k1 k2]]
  (if-let [[_ old] (find e k1)]
    (-> e
        (assoc k2 old)
        (dissoc k1))
    e))

(defn replace-keys [e kvs]
  (reduce replace-key e kvs))

(defn comp-without-nils [& fs]
  ;; like comp, except if (f x) => nil, pretend it returned x
  (fn [x]
    (reduce (fn [v f]
              (if (= v :tictag.resources/unprocessable)
                v
                (if-let [new (f v)]
                  new
                  v)))
            x
            fs)))

(defn process [spec ctx e-keys & [fns]]
  (if-not (#{:put :post} (get-in ctx [:request :request-method]))
    nil
    (let [e (s/conform spec (params ctx))]
      (if (= e :clojure.spec.alpha/invalid)
        :tictag.resources/unprocessable
        (let [e (select-keys e e-keys)]
          (if-not (seq e)
            :tictag.resources/unprocessable
            (let [fns (if (fn? fns) [fns] fns)]
              ((apply comp-without-nils fns) e))))))))

(defn processable? [spec ctx e-keys]
  (let [e (process spec ctx e-keys)]
    (if (= e :tictag.resources/unprocessable)
      [false nil]
      [true e])))
