(ns ring.middleware.head-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.head :refer [wrap-head]]))

(def handler
  (wrap-head
   (fn [req]
     {:status 200
      :headers {"X-Method" (name (:request-method req))}
      :body "the body"})))

(deftest ^:async test-wrap-head
  (testing "HEAD requests are converted to GET with nil body"
    (let [resp (await (handler {:request-method :head}))]
      (is (= 200 (:status resp)))
      (is (nil? (:body resp)))
      (is (= "get" (get-in resp [:headers "X-Method"])))))
  (testing "GET requests pass through"
    (let [resp (await (handler {:request-method :get}))]
      (is (= "the body" (:body resp)))
      (is (= "get" (get-in resp [:headers "X-Method"]))))))
