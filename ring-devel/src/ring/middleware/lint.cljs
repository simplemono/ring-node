(ns ring.middleware.lint
  "Middleware that checks requests and responses for correctness against the
  ring-node spec (SPEC.md), throwing an exception on violations. Useful for
  testing adapters and middleware."
  (:require [clojure.string :as str]
            [ring.util.async :as async]
            [ring.util.io :as io]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as wsp]))

(defn- lint-error [message data]
  (throw (ex-info (str "Ring lint error: " message) data)))

(defn- check [value pred message]
  (when-not (pred value)
    (lint-error message {:value value})))

(defn- opt [pred]
  (fn [x] (or (nil? x) (pred x))))

(defn- check-headers [headers lowercase?]
  (check headers map? "headers must be a map")
  (doseq [[k v] headers]
    (check k string? "header names must be strings")
    (when lowercase?
      (check k #(= % (str/lower-case %)) "request header names must be lowercase"))
    (check v #(or (string? %)
                  (and (or (vector? %) (seq? %)) (every? string? %)))
           "header values must be strings or collections of strings")))

(defn check-request
  "Check a request map against the ring-node spec, throwing an exception on
  violations."
  [request]
  (check request map? "request must be a map")
  (check (:request-method request)
         #(and (keyword? %) (= (name %) (str/lower-case (name %))))
         "request-method must be a lowercase keyword")
  (check (:uri request) #(and (string? %) (str/starts-with? % "/"))
         "uri must be a string starting with /")
  (check (:scheme request) #{:http :https :ws :wss}
         "scheme must be :http, :https, :ws or :wss")
  (check (:protocol request) string? "protocol must be a string")
  (check (:server-name request) string? "server-name must be a string")
  (check (:server-port request) integer? "server-port must be an integer")
  (check (:remote-addr request) string? "remote-addr must be a string")
  (check (:query-string request) (opt string?)
         "query-string must be nil or a string")
  (check (:body request) (opt io/readable-stream?)
         "body must be nil or a Readable stream")
  (check-headers (:headers request) true)
  request)

(defn- valid-body? [body]
  (or (nil? body)
      (string? body)
      (js/Buffer.isBuffer body)
      (io/readable-stream? body)
      (seqable? body)))

(defn check-response
  "Check a response map against the ring-node spec, throwing an exception on
  violations."
  [response]
  (check response map? "response must be a map")
  (if (ws/websocket-response? response)
    (check (:ring.websocket/listener response)
           #(satisfies? wsp/Listener %)
           "websocket listener must satisfy ring.websocket.protocols/Listener")
    (do (check (:status response) #(and (integer? %) (<= 100 % 599))
               "status must be an integer between 100 and 599")
        (check-headers (:headers response) false)
        (check (:body response) valid-body?
               "body must be nil, a string, a Buffer, a Readable stream, or seqable")))
  response)

(defn wrap-lint
  "Wrap a handler to validate incoming requests and outgoing responses
  against the ring-node spec, throwing an exception on violations."
  [handler]
  (fn [request]
    (check-request request)
    (async/then (async/call handler request)
                check-response)))
