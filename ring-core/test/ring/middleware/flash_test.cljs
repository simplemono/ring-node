(ns ring.middleware.flash-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.flash :refer [wrap-flash]]))

(deftest ^:async flash-is-added-to-session
  (let [message  {:error "Failed!"}
        handler  (wrap-flash (constantly {:status 200 :headers {} :flash message}))
        resp     (await (handler {:session {}}))]
    (is (= {:_flash message} (:session resp)))))

(deftest ^:async flash-is-retrieved-from-session
  (let [message {:error "Failed!"}
        handler (wrap-flash
                 (fn [req]
                   (is (= message (:flash req)))
                   (is (= {} (:session req)))
                   {:status 200 :headers {}}))
        resp    (await (handler {:session {:_flash message}}))]
    (testing "flash is removed from the next session"
      (is (nil? (get-in resp [:session :_flash]))))))

(deftest ^:async flash-doesnt-wipe-session
  (let [message {:error "Failed!"}
        handler (wrap-flash
                 (constantly {:status 200 :headers {} :flash message}))
        resp    (await (handler {:session {:foo "bar"}}))]
    (is (= {:foo "bar" :_flash message} (:session resp)))))

(deftest ^:async flash-overwrites-session-changes
  (let [handler (wrap-flash
                 (constantly {:status 200 :headers {}
                              :session {:foo "bar"} :flash "!"}))
        resp    (await (handler {:session {}}))]
    (is (= {:foo "bar" :_flash "!"} (:session resp)))))

(deftest ^:async flash-nil-response-passes-through
  (let [handler (wrap-flash (constantly nil))]
    (is (nil? (await (handler {:session {:_flash "x"}}))))))
