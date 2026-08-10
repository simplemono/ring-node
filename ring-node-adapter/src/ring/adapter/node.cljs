(ns ring.adapter.node
  "A Ring adapter that uses the node:http server, with websocket support
  provided by the ws npm package."
  (:require ["node:http" :as http]
            ["ws" :as ws-lib]
            [clojure.string :as str]
            [ring.core.protocols :as protocols]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as wsp]))

;; -- request maps -----------------------------------------------------------

(defn- normalize-headers [^js headers]
  (persistent!
   (reduce (fn [m k]
             (let [v (unchecked-get headers k)]
               (assoc! m k (if (array? v) (.join v ", ") v))))
           (transient {})
           (js/Object.keys headers))))

(defn- host-name [host]
  (when host
    (if (str/starts-with? host "[")
      (some->> (str/index-of host "]") inc (subs host 0))
      (if-let [i (str/index-of host ":")]
        (subs host 0 i)
        host))))

(defn- host-port [host]
  (when host
    (let [i           (str/last-index-of host ":")
          bracket-end (str/index-of host "]")]
      (when (and i (or (nil? bracket-end) (> i bracket-end)))
        (let [n (js/parseInt (subs host (inc i)) 10)]
          (when-not (js/isNaN n) n))))))

(defn- build-request-map [^js req]
  (let [socket  (.-socket req)
        url     (or (.-url req) "/")
        qi      (str/index-of url "?")
        headers (normalize-headers (.-headers req))
        host    (get headers "host")]
    {:server-port    (or (host-port host) (.-localPort socket))
     :server-name    (or (host-name host) (.-localAddress socket))
     :remote-addr    (.-remoteAddress socket)
     :uri            (if qi (subs url 0 qi) url)
     :query-string   (when qi (subs url (inc qi)))
     :scheme         (if (.-encrypted socket) :https :http)
     :request-method (keyword (str/lower-case (.-method req)))
     :protocol       (str "HTTP/" (.-httpVersion req))
     :headers        headers
     :body           req}))

;; -- http responses ---------------------------------------------------------

(defn- write-head [^js res response]
  (let [headers (reduce-kv (fn [^js o k v]
                             (unchecked-set o k (if (coll? v)
                                                  (into-array (map str v))
                                                  (str v)))
                             o)
                           #js {}
                           (:headers response))]
    (.writeHead res (:status response) headers)))

(defn- default-error-logger [e]
  (js/console.error e))

(defn- error-logger [opts]
  (:error-logger opts default-error-logger))

(defn- handle-error [e ^js res opts]
  ((error-logger opts) e)
  (if (.-headersSent res)
    (.destroy (.-socket res))
    (do (.writeHead res 500 #js {"content-type" "text/plain"})
        (.end res "Internal Server Error"))))

(defn- ^:async handle-request [handler ^js req ^js res opts]
  (try
    (.on res "error" (fn [_]))
    (let [request  (build-request-map req)
          response (await (js/Promise.resolve (handler request)))]
      (cond
        (ws/websocket-response? response)
        (throw (ex-info "Websocket response to a request with no upgrade header"
                        {:response response}))

        (map? response)
        (do (write-head res response)
            (await (protocols/write-body-to-stream (:body response) response res)))

        :else
        (throw (ex-info "Handler returned an invalid response"
                        {:response response}))))
    (catch :default e
      (handle-error e res opts))))

;; -- websockets -------------------------------------------------------------

(deftype NodeWebSocket [^js websock]
  wsp/Socket
  (-open? [_]
    (= (.-readyState websock) 1))
  (-send [_ message]
    (js/Promise.
     (fn [resolve reject]
       (.send websock message
              (fn [err] (if (some? err) (reject err) (resolve nil)))))))
  (-ping [_ data]
    (js/Promise.
     (fn [resolve reject]
       (.ping websock data false
              (fn [err] (if (some? err) (reject err) (resolve nil)))))))
  (-pong [_ data]
    (js/Promise.
     (fn [resolve reject]
       (.pong websock data false
              (fn [err] (if (some? err) (reject err) (resolve nil)))))))
  (-close [_ code reason]
    (js/Promise.
     (fn [resolve _]
       (.once websock "close" (fn [& _] (resolve nil)))
       (.close websock code reason)))))

(defn- connect-listener [^js websock listener]
  (let [socket (NodeWebSocket. websock)]
    (.on websock "message"
         (fn [data is-binary]
           (wsp/on-message listener socket
                           (if is-binary data (.toString data "utf8")))))
    (.on websock "ping"
         (fn [data]
           ;; The server is created with autoPong disabled, so the spec's
           ;; ping/pong contract is under listener control.
           (if (satisfies? wsp/PingListener listener)
             (wsp/on-ping listener socket data)
             (wsp/-pong socket data))))
    (.on websock "pong"
         (fn [data] (wsp/on-pong listener socket data)))
    (.on websock "error"
         (fn [err] (wsp/on-error listener socket err)))
    (.on websock "close"
         (fn [code reason]
           (wsp/on-close listener socket code (.toString reason "utf8"))))
    (wsp/on-open listener socket)))

(defn- make-wss []
  (let [WebSocketServer (.-WebSocketServer ws-lib)]
    (WebSocketServer.
     #js {:noServer true
          :autoPong false
          ;; The subprotocol chosen by the response is stashed on the Node
          ;; request object by handle-upgrade before handleUpgrade is called.
          :handleProtocols
          (fn [_protocols ^js req]
            (or (unchecked-get req "ringWebsocketProtocol") false))})))

(defn- write-raw-response
  "Serialize a response map directly onto a socket. Used for non-101 responses
  to upgrade requests, where node:http provides no response object."
  [^js socket response]
  (let [status   (:status response 500)
        reason   (or (unchecked-get http/STATUS_CODES (str status)) "")
        body     (:body response)
        body-str (if (string? body) body "")
        headers  (-> (into {}
                           (map (fn [[k v]]
                                  [(str/lower-case k)
                                   (if (coll? v) (str/join ", " v) (str v))]))
                           (:headers response))
                     (dissoc "content-length" "transfer-encoding")
                     (assoc "content-length" (str (js/Buffer.byteLength body-str "utf8"))
                            "connection" "close"))]
    (.write socket
            (str "HTTP/1.1 " status " " reason "\r\n"
                 (str/join "\r\n" (map (fn [[k v]] (str k ": " v)) headers))
                 "\r\n\r\n"
                 body-str))
    (.end socket)))

(defn- ^:async handle-upgrade [handler ^js wss ^js req ^js socket ^js head opts]
  (try
    (let [request  (build-request-map req)
          response (await (js/Promise.resolve (handler request)))]
      (cond
        (ws/websocket-response? response)
        (let [listener (:ring.websocket/listener response)]
          (when-let [protocol (:ring.websocket/protocol response)]
            (unchecked-set req "ringWebsocketProtocol" protocol))
          (.handleUpgrade wss req socket head
                          (fn [websock]
                            (connect-listener websock listener))))

        (map? response)
        (write-raw-response socket response)

        :else
        (throw (ex-info "Handler returned an invalid response"
                        {:response response}))))
    (catch :default e
      ((error-logger opts) e)
      (write-raw-response socket {:status 500
                                  :headers {"content-type" "text/plain"}
                                  :body "Internal Server Error"}))))

;; -- server -----------------------------------------------------------------

(defn run-server
  "Start a node:http server to serve the given handler. Returns a js/Promise
  that resolves to the server once it is listening.

  Handlers follow the ring-node spec: they take a request map and return a
  response map or a thenable of one. Websocket responses are supported for
  upgrade requests.

  Options:
    :port         - the port to listen on (defaults to 3000; 0 picks a free port)
    :host         - the hostname to listen on (defaults to all interfaces)
    :error-logger - a function called with an error when a handler throws or
                    rejects (defaults to js/console.error)"
  ([handler]
   (run-server handler {}))
  ([handler {:keys [port host] :or {port 3000} :as opts}]
   (let [server (http/createServer
                 (fn [req res] (handle-request handler req res opts)))
         wss    (make-wss)]
     (.on server "upgrade"
          (fn [req socket head]
            (handle-upgrade handler wss req socket head opts)))
     (js/Promise.
      (fn [resolve reject]
        (.once server "error" reject)
        (if host
          (.listen server port host (fn [] (resolve server)))
          (.listen server port (fn [] (resolve server)))))))))

(defn server-port
  "The local port a running server is bound to."
  [^js server]
  (.-port (.address server)))

(defn stop-server
  "Stop a server started by run-server, closing open connections. Returns a
  js/Promise that resolves once the server has closed."
  [^js server]
  (js/Promise.
   (fn [resolve reject]
     (.closeAllConnections server)
     (.close server (fn [err] (if (some? err) (reject err) (resolve nil)))))))
