(ns ring.middleware.not-modified
  "Middleware that returns a 304 Not Modified response for responses with
  Last-Modified headers."
  (:require [ring.util.async :as async]
            [ring.util.io :refer [close!]]
            [ring.util.response :refer [get-header find-header]]
            [ring.util.time :refer [parse-date]]))

(defn- etag-match? [request response]
  (when-let [etag (get-header response "ETag")]
    (= etag (get-header request "if-none-match"))))

(defn- date-header [response header]
  (when-let [http-date (get-header response header)]
    (parse-date http-date)))

(defn- not-modified-since? [request response]
  (let [modified-date  (date-header response "Last-Modified")
        modified-since (date-header request "if-modified-since")]
    (and modified-date
         modified-since
         (>= (.getTime modified-since) (.getTime modified-date)))))

(defn- read-request? [request]
  (#{:get :head} (:request-method request)))

(defn- ok-response? [response]
  (= (:status response) 200))

(defn- cached-response? [request response]
  (let [modified-since (get-header request "if-modified-since")
        if-none-match  (get-header request "if-none-match")]
    (if (and modified-since if-none-match)
      (and (not-modified-since? request response)
           (etag-match? request response))
      (or (not-modified-since? request response)
          (etag-match? request response)))))

(defn- dissoc-header [response header]
  (if-some [[k _] (find-header response header)]
    (update response :headers dissoc k)
    response))

(defn not-modified-response
  "Returns 304 or original response based on response and request.
  See: wrap-not-modified."
  [response request]
  (if (and (read-request? request)
           (ok-response? response)
           (cached-response? request response))
    (do (close! (:body response))
        (-> response
            (assoc :status 304)
            (dissoc-header "Content-Length")
            (assoc :body nil)))
    response))

(defn wrap-not-modified
  "Middleware that returns a 304 Not Modified from the wrapped handler if the
  handler response has an ETag or Last-Modified header, and the request has a
  If-None-Match or If-Modified-Since header that matches the response."
  [handler]
  (fn [request]
    (async/then (async/call handler request)
                #(not-modified-response % request))))
