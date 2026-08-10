(ns ring.middleware.stacktrace-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace
                                                wrap-stacktrace-log]]))

(deftest ^:async test-wrap-stacktrace-sync-throw
  (let [logged  (atom nil)
        handler (wrap-stacktrace (fn [_] (throw (js/Error. "kaboom")))
                                 {:logger #(reset! logged %)})
        resp    (await (handler {:uri "/"}))]
    (is (= 500 (:status resp)))
    (is (= "text/html" (get-in resp [:headers "Content-Type"])))
    (is (re-find #"kaboom" (:body resp)))
    (is (re-find #"kaboom" @logged))))

(deftest ^:async test-wrap-stacktrace-rejection
  (let [handler (wrap-stacktrace
                 (fn ^:async failing [_]
                   (throw (ex-info "async boom" {})))
                 {:logger (fn [_])})
        resp    (await (handler {:uri "/"}))]
    (is (= 500 (:status resp)))
    (is (re-find #"async boom" (:body resp)))))

(deftest ^:async test-wrap-stacktrace-html-escaped
  (let [handler (wrap-stacktrace (fn [_] (throw (js/Error. "<script>alert(1)</script>")))
                                 {:logger (fn [_])})
        resp    (await (handler {:uri "/"}))]
    (is (not (re-find #"<script>" (:body resp))))
    (is (re-find #"&lt;script&gt;" (:body resp)))))

(deftest ^:async test-wrap-stacktrace-passes-success-through
  (let [handler (wrap-stacktrace (constantly {:status 200 :headers {} :body "ok"})
                                 {:logger (fn [_])})
        resp    (await (handler {:uri "/"}))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:body resp)))))

(deftest ^:async test-wrap-stacktrace-log-rethrows
  (let [logged  (atom nil)
        handler (wrap-stacktrace-log (fn [_] (throw (js/Error. "logged")))
                                     {:logger #(reset! logged %)})]
    (try
      (await (handler {:uri "/"}))
      (is false "expected rejection")
      (catch :default e
        (is (= "logged" (.-message e)))
        (is (re-find #"logged" @logged))))))
