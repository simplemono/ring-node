(ns ring.middleware.not-modified-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]))

(defn- handler-with [headers]
  (wrap-not-modified
   (constantly {:status 200
                :headers headers
                :body "the body"})))

(deftest ^:async test-etag-match
  (let [handler (handler-with {"ETag" "known-etag"})]
    (testing "matching etag returns 304 with no body"
      (let [resp (await (handler {:request-method :get
                                  :headers {"if-none-match" "known-etag"}}))]
        (is (= 304 (:status resp)))
        (is (nil? (:body resp)))))
    (testing "non-matching etag returns response unchanged"
      (let [resp (await (handler {:request-method :get
                                  :headers {"if-none-match" "other-etag"}}))]
        (is (= 200 (:status resp)))
        (is (= "the body" (:body resp)))))))

(deftest ^:async test-not-modified-since
  (let [handler (handler-with {"Last-Modified" "Sat, 04 Jan 2014 01:13:20 GMT"})]
    (testing "request modified-since after last-modified returns 304"
      (let [resp (await (handler {:request-method :get
                                  :headers {"if-modified-since"
                                            "Sun, 05 Jan 2014 00:00:00 GMT"}}))]
        (is (= 304 (:status resp)))))
    (testing "equal dates return 304"
      (let [resp (await (handler {:request-method :get
                                  :headers {"if-modified-since"
                                            "Sat, 04 Jan 2014 01:13:20 GMT"}}))]
        (is (= 304 (:status resp)))))
    (testing "request modified-since before last-modified returns 200"
      (let [resp (await (handler {:request-method :get
                                  :headers {"if-modified-since"
                                            "Fri, 03 Jan 2014 00:00:00 GMT"}}))]
        (is (= 200 (:status resp)))))))

(deftest ^:async test-content-length-header-removed-on-304
  (let [handler (wrap-not-modified
                 (constantly {:status 200
                              :headers {"ETag" "e" "Content-Length" "8"}
                              :body "the body"}))
        resp    (await (handler {:request-method :get
                                 :headers {"if-none-match" "e"}}))]
    (is (nil? (get-in resp [:headers "Content-Length"])))))

(deftest ^:async test-no-modification-info-or-write-methods
  (let [handler (handler-with {})]
    (testing "no etag or last-modified"
      (is (= 200 (:status (await (handler {:request-method :get
                                           :headers {"if-none-match" "e"}})))))))
  (let [handler (handler-with {"ETag" "e"})]
    (testing "POST requests are not cached"
      (is (= 200 (:status (await (handler {:request-method :post
                                           :headers {"if-none-match" "e"}}))))))))
