(ns tictag.server-test
  (:require [tictag.server :as server]
            [clojure.test :refer :all]))

(deftest parse-sms-body
  (is (= (server/parse-sms-body "sleep")
         {:command :sleep
          :args {}}))
  (is (= (server/parse-sms-body "123 tired")
         {:command :tag-ping-by-id
          :args {:id "123" :tags ["tired"]}}))
  (is (= (server/parse-sms-body "123 tired bedtime")
         {:command :tag-ping-by-id
          :args {:id "123"
                 :tags ["tired" "bedtime"]}}))
  (is (= (server/parse-sms-body "1485544834000 tired")
         {:command :tag-ping-by-long-time
          :args {:long-time 1485544834000
                 :tags ["tired"]}}))
  (is (= (server/parse-sms-body "1485544834000 tired bedtime")
         {:command :tag-ping-by-long-time
          :args {:long-time 1485544834000
                 :tags ["tired" "bedtime"]}}))
  (is (= (server/parse-sms-body "tired bedtime")
         {:command :tag-last-ping
          :args {:tags ["tired" "bedtime"]}})))

