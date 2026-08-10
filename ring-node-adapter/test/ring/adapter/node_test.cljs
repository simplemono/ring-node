(ns ring.adapter.node-test
  (:require ["node:http" :as http]
            ["ws" :as ws-lib]
            [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [ring.adapter.node :as node]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.io :as io]
            [ring.util.request :as request]
            [ring.websocket :as ws]))

(defn- http-request
  ([port path] (http-request port path {}))
  ([port path {:keys [method headers body] :or {method "GET"}}]
   (js/Promise.
    (fn [resolve reject]
      (let [req (http/request
                 #js {:port port :path path :method method
                      :headers (clj->js (or headers {}))}
                 (fn [^js res]
                   (let [chunks #js []]
                     (.on res "data" (fn [c] (.push chunks c)))
                     (.on res "end"
                          (fn []
                            (resolve
                             {:status  (.-statusCode res)
                              :headers (js->clj (.-headers res))
                              :body    (.toString (js/Buffer.concat chunks) "utf8")}))))))]
        (.on req "error" reject)
        (when body (.write req body))
        (.end req))))))

(defn- ^:async with-server
  "Run f with [server port], stopping the server afterwards."
  [handler f]
  (let [server (await (node/run-server handler {:port 0}))]
    (try
      (await (f server (node/server-port server)))
      (finally
        (await (node/stop-server server))))))

(deftest ^:async test-http-round-trip
  (await
   (with-server
     (fn [request]
       {:status 200
        :headers {"Content-Type" "text/plain"}
        :body (str "hello " (:uri request))})
     (fn ^:async check [_ port]
       (let [res (await (http-request port "/world"))]
         (is (= 200 (:status res)))
         (is (= "hello /world" (:body res)))
         (is (= "text/plain" (get-in res [:headers "content-type"]))))))))

(deftest ^:async test-request-map-contents
  (await
   (with-server
     (fn [request]
       {:status 200 :headers {"Content-Type" "application/edn"}
        :body (pr-str (dissoc request :body))})
     (fn ^:async check [_ port]
       (let [res     (await (http-request port "/a/b?x=1&y=2"))
             request (reader/read-string (:body res))]
         (is (= "/a/b" (:uri request)))
         (is (= "x=1&y=2" (:query-string request)))
         (is (= :get (:request-method request)))
         (is (= :http (:scheme request)))
         (is (= "HTTP/1.1" (:protocol request)))
         (is (= port (:server-port request)))
         (is (string? (:remote-addr request)))
         (is (= "localhost" (:server-name request))
             "server-name comes from the host header"))))))

(deftest ^:async test-async-handler-with-params
  (await
   (with-server
     (wrap-params
      (fn ^:async echo [request]
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body (get-in request [:params "name"] "nobody")}))
     (fn ^:async check [_ port]
       (is (= "ring" (:body (await (http-request port "/?name=ring")))))))))

(deftest ^:async test-request-body-is-readable
  (await
   (with-server
     (fn ^:async echo-body [request]
       {:status 200 :headers {}
        :body (str "got: " (await (request/body-string request)))})
     (fn ^:async check [_ port]
       (let [res (await (http-request port "/" {:method "POST" :body "the payload"}))]
         (is (= "got: the payload" (:body res))))))))

(deftest ^:async test-stream-response-body
  (await
   (with-server
     (fn [_] {:status 200 :headers {} :body (io/string-stream "streamed body")})
     (fn ^:async check [_ port]
       (is (= "streamed body" (:body (await (http-request port "/")))))))))

(deftest ^:async test-multi-value-headers
  (await
   (with-server
     (fn [_] {:status 200
              :headers {"Set-Cookie" ["a=1" "b=2"]}
              :body ""})
     (fn ^:async check [_ port]
       (let [res (await (http-request port "/"))]
         (is (= ["a=1" "b=2"] (get-in res [:headers "set-cookie"]))))))))

(deftest ^:async test-thrown-error-returns-500
  (let [server (await (node/run-server (fn [_] (throw (js/Error. "sync boom")))
                                       {:port 0 :error-logger (fn [_])}))
        port   (node/server-port server)
        res    (await (http-request port "/"))]
    (is (= 500 (:status res)))
    (is (= "Internal Server Error" (:body res)))
    (await (node/stop-server server))))

(deftest ^:async test-rejected-error-returns-500
  (let [errors  (atom [])
        handler (fn ^:async failing [_] (throw (ex-info "async boom" {})))]
    (let [server (await (node/run-server handler {:port 0
                                                  :error-logger #(swap! errors conj %)}))
          port   (node/server-port server)
          res    (await (http-request port "/"))]
      (is (= 500 (:status res)))
      (is (= 1 (count @errors)))
      (is (= "async boom" (ex-message (first @errors))))
      (await (node/stop-server server)))))

(deftest ^:async test-websocket-echo
  (await
   (with-server
     (fn [request]
       (if (ws/upgrade-request? request)
         {:ring.websocket/listener
          {:on-message (fn [socket msg] (ws/send socket (str "echo: " msg)))}}
         {:status 200 :headers {} :body "plain"}))
     (fn ^:async check [_ port]
       (let [WebSocket (.-WebSocket ws-lib)
             result
             (await
              (js/Promise.
               (fn [resolve reject]
                 (let [client (WebSocket. (str "ws://127.0.0.1:" port "/"))]
                   (.on client "open" (fn [] (.send client "hello")))
                   (.on client "message"
                        (fn [data]
                          (.close client)
                          (resolve (.toString data "utf8"))))
                   (.on client "error" reject)))))]
         (is (= "echo: hello" result)))
       ;; the same handler still serves plain http
       (is (= "plain" (:body (await (http-request port "/")))))))))

(deftest ^:async test-websocket-close-listener
  (await
   (with-server
     (fn [_]
       {:ring.websocket/listener
        {:on-open (fn [socket] (ws/close socket 4000 "going away"))}})
     (fn ^:async check [_ port]
       (let [WebSocket (.-WebSocket ws-lib)
             [code reason]
             (await
              (js/Promise.
               (fn [resolve reject]
                 (let [client (WebSocket. (str "ws://127.0.0.1:" port "/"))]
                   (.on client "close"
                        (fn [code reason]
                          (resolve [code (.toString reason "utf8")])))
                   (.on client "error" reject)))))]
         (is (= 4000 code))
         (is (= "going away" reason)))))))

(deftest ^:async test-upgrade-request-can-be-rejected
  (await
   (with-server
     (fn [request]
       (if (ws/upgrade-request? request)
         {:status 403 :headers {"Content-Type" "text/plain"} :body "no websockets here"}
         {:status 200 :headers {} :body "ok"}))
     (fn ^:async check [_ port]
       (let [WebSocket (.-WebSocket ws-lib)
             result
             (await
              (js/Promise.
               (fn [resolve _]
                 (let [client (WebSocket. (str "ws://127.0.0.1:" port "/"))]
                   (.on client "unexpected-response"
                        (fn [_ ^js res] (resolve (.-statusCode res))))
                   (.on client "error" (fn [e] (resolve (.-message e))))))))]
         (is (= 403 result)))))))
