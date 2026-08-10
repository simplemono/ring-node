(ns ring.middleware.lint-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.lint :refer [wrap-lint]]
            [ring.util.io :as io]))

(def valid-request
  {:request-method :get
   :uri            "/"
   :scheme         :http
   :protocol       "HTTP/1.1"
   :server-name    "localhost"
   :server-port    8080
   :remote-addr    "127.0.0.1"
   :headers        {"host" "localhost"}})

(def valid-response
  {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})

(defn- ^:async lint-error-message [handler request]
  (try
    (await ((wrap-lint handler) request))
    nil
    (catch :default e
      (ex-message e))))

(deftest ^:async test-valid-request-response-passes
  (let [resp (await ((wrap-lint (constantly valid-response)) valid-request))]
    (is (= valid-response resp))))

(deftest ^:async test-valid-body-types
  (doseq [body [nil "str" (js/Buffer.from "b") (io/string-stream "s") (list "a")]]
    (is (nil? (await (lint-error-message
                      (constantly (assoc valid-response :body body))
                      valid-request))))))

(deftest ^:async test-invalid-requests
  (is (re-find #"request-method"
               (await (lint-error-message (constantly valid-response)
                                          (assoc valid-request :request-method "GET")))))
  (is (re-find #"uri"
               (await (lint-error-message (constantly valid-response)
                                          (assoc valid-request :uri "no-slash")))))
  (is (re-find #"lowercase"
               (await (lint-error-message (constantly valid-response)
                                          (assoc valid-request
                                                 :headers {"Host" "x"}))))))

(deftest ^:async test-invalid-responses
  (is (re-find #"status"
               (await (lint-error-message (constantly (assoc valid-response :status 42))
                                          valid-request))))
  (is (re-find #"status"
               (await (lint-error-message (constantly (assoc valid-response :status "200"))
                                          valid-request))))
  (is (re-find #"body"
               (await (lint-error-message (constantly (assoc valid-response :body 42))
                                          valid-request)))))

(deftest ^:async test-websocket-response
  (is (nil? (await (lint-error-message
                    (constantly {:ring.websocket/listener {:on-open (fn [_])}})
                    valid-request))))
  (is (re-find #"listener"
               (await (lint-error-message
                       (constantly {:ring.websocket/listener 42})
                       valid-request)))))
