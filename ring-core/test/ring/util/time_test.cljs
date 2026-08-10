(ns ring.util.time-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.util.time :refer [format-date parse-date]]))

(def ^:private timestamp 1212492610000) ;; 2008-06-03T11:30:10Z

(deftest test-parse-date
  (testing "RFC 1123"
    (is (= timestamp (.getTime (parse-date "Tue, 03 Jun 2008 11:30:10 GMT")))))
  (testing "RFC 1036"
    (is (= timestamp (.getTime (parse-date "Tuesday, 03-Jun-08 11:30:10 GMT")))))
  (testing "asctime"
    (is (= timestamp (.getTime (parse-date "Tue Jun  3 11:30:10 2008")))))
  (testing "quoted"
    (is (= timestamp (.getTime (parse-date "'Tue, 03 Jun 2008 11:30:10 GMT'")))))
  (testing "unparseable dates return nil"
    (is (nil? (parse-date "not-a-date")))
    (is (nil? (parse-date "Tue, 99 Zzz 2008 11:30:10 GMT")))))

(deftest test-format-date
  (is (= "Tue, 03 Jun 2008 11:30:10 GMT" (format-date (js/Date. timestamp)))))

(deftest test-round-trip
  (let [date (js/Date. timestamp)]
    (is (= timestamp (.getTime (parse-date (format-date date)))))))
