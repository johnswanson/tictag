(ns tictag.cli-test
  (:require [tictag.cli :as cli]
            [clojure.test :refer :all]))

(deftest parse-body
  (is (= (cli/parse-body "sleep")
         {:command :sleep
          :args {}}))
  (is (= (cli/parse-body "123 tired")
         {:command :tag-ping-by-id
          :args {:id "123" :tags ["tired"]}}))
  (is (= (cli/parse-body "123 tired bedtime")
         {:command :tag-ping-by-id
          :args {:id "123"
                 :tags ["tired" "bedtime"]}}))
  (is (= (cli/parse-body "1485544834000 tired")
         {:command :tag-ping-by-long-time
          :args {:long-time 1485544834000
                 :tags ["tired"]}}))
  (is (= (cli/parse-body "1485544834000 tired bedtime")
         {:command :tag-ping-by-long-time
          :args {:long-time 1485544834000
                 :tags ["tired" "bedtime"]}}))
  (is (= (cli/parse-body "tired bedtime")
         {:command :tag-last-ping
          :args {:tags ["tired" "bedtime"]}})))
