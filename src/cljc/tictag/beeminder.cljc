(ns tictag.beeminder)

(defmulti match? (fn [a _] (type a)))

(defmethod match? nil [_ _] false)

(defmethod match? #?(:clj clojure.lang.Keyword :cljs cljs.core/Keyword)
  [a b]
  (b a))

(defmethod match? #?(:clj clojure.lang.PersistentVector :cljs cljs.core/PersistentVector)
  [[pred & args] b]
  (case pred
    :and (every? #(match? % b) args)
    :or  (some #(match? % b) args)))

