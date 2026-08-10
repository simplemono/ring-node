(ns ring.websocket
  "Protocols and utility functions for websocket support."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [ring.websocket.protocols :as p]))

;; Plain maps of callback functions may be used as listeners. CLJS has no
;; single IPersistentMap type to extend, so both concrete map types get the
;; same implementations.

(defn- callback [m k & args]
  (when-let [kv (find m k)]
    (apply (val kv) args)))

(defn- ping-callback [m socket data]
  (if-let [kv (find m :on-ping)]
    ((val kv) socket data)
    (p/-pong socket data)))

(extend-protocol p/Listener
  PersistentArrayMap
  (on-open [m socket] (callback m :on-open socket))
  (on-message [m socket message] (callback m :on-message socket message))
  (on-pong [m socket data] (callback m :on-pong socket data))
  (on-error [m socket error] (callback m :on-error socket error))
  (on-close [m socket code reason] (callback m :on-close socket code reason))
  PersistentHashMap
  (on-open [m socket] (callback m :on-open socket))
  (on-message [m socket message] (callback m :on-message socket message))
  (on-pong [m socket data] (callback m :on-pong socket data))
  (on-error [m socket error] (callback m :on-error socket error))
  (on-close [m socket code reason] (callback m :on-close socket code reason)))

(extend-protocol p/PingListener
  PersistentArrayMap
  (on-ping [m socket data] (ping-callback m socket data))
  PersistentHashMap
  (on-ping [m socket data] (ping-callback m socket data)))

(defn open?
  "Returns true if the Socket is open, false otherwise."
  [socket]
  (boolean (p/-open? socket)))

(defn send
  "Sends text or binary data via a websocket. Returns a js/Promise, or calls
  the supplied callback functions on completion. A convenient wrapper for the
  -send protocol method."
  ([socket message]
   (p/-send socket message))
  ([socket message succeed fail]
   (.then (p/-send socket message) (fn [_] (succeed)) fail)))

(defn ping
  "Sends a ping message via a websocket, with an optional js/Buffer that may
  contain custom session data. Returns a js/Promise. A convenient wrapper for
  the -ping protocol method."
  ([socket]
   (p/-ping socket (js/Buffer.alloc 0)))
  ([socket data]
   (p/-ping socket data)))

(defn pong
  "Sends an unsolicited pong message via a websocket, with an optional
  js/Buffer that may contain custom session data. Returns a js/Promise. A
  convenient wrapper for the -pong protocol method."
  ([socket]
   (p/-pong socket (js/Buffer.alloc 0)))
  ([socket data]
   (p/-pong socket data)))

(defn close
  "Closes the websocket, with an optional custom integer status code and
  reason string. Returns a js/Promise."
  ([socket]
   (p/-close socket 1000 "Normal Closure"))
  ([socket code reason]
   (p/-close socket code reason)))

(defn upgrade-request?
  "Returns true if the request map is a websocket upgrade request."
  [request]
  (let [{{:strs [connection upgrade]} :headers} request]
    (boolean
     (and upgrade
          connection
          (re-find #"(?i)\bupgrade\b" connection)
          (= "websocket" (str/lower-case upgrade))))))

(defn websocket-response?
  "Returns true if the response contains a websocket listener."
  [response]
  (contains? response ::listener))

(defn request-protocols
  "Returns a collection of websocket subprotocols from a request map."
  [request]
  (some-> (:headers request)
          (get "sec-websocket-protocol")
          (str/split #"\s*,\s*")))
