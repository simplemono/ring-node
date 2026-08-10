(ns ring.middleware.stacktrace
  "Middleware that catches exceptions thrown or rejected by the handler, and
  reports the error and stacktrace via a webpage and log.

  This middleware is for debugging purposes, and should be limited to
  development environments. Run node with --enable-source-maps to see
  ClojureScript line numbers in the stacktraces."
  (:require [clojure.string :as str]
            [ring.util.async :as async]))

(defn- error-stack [e]
  (or (some-> (.-stack ^js e) str)
      (pr-str e)))

(defn- error-message [e]
  (or (ex-message e)
      (some-> (.-message ^js e) str)
      (pr-str e)))

(defn wrap-stacktrace-log
  "Wrap a handler such that errors are logged and re-raised.

  Accepts the following option:

  :logger - a function called with the error's stacktrace string (defaults
            to js/console.error)"
  ([handler]
   (wrap-stacktrace-log handler {}))
  ([handler {:keys [logger] :or {logger js/console.error}}]
   (fn [request]
     (.catch (async/call handler request)
             (fn [e]
               (logger (error-stack e))
               (throw e))))))

(defn- escape-html [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- html-error-page [e]
  (str "<!DOCTYPE html><html><head><title>Ring: Stacktrace</title><style>"
       "body{font-family:monospace;margin:2em;color:#333}"
       "h1{color:#a00;font-size:1.2em}"
       "pre{background:#f6f6f6;border:1px solid #ddd;padding:1em;overflow-x:auto}"
       "p{color:#777}"
       "</style></head><body>"
       "<h1>" (escape-html (error-message e)) "</h1>"
       "<pre>" (escape-html (error-stack e)) "</pre>"
       "<p>Run node with --enable-source-maps for ClojureScript line numbers.</p>"
       "</body></html>"))

(defn wrap-stacktrace-web
  "Wrap a handler such that errors are caught and a HTML response describing
  the error is returned."
  [handler]
  (fn [request]
    (.catch (async/call handler request)
            (fn [e]
              {:status  500
               :headers {"Content-Type" "text/html"}
               :body    (html-error-page e)}))))

(defn wrap-stacktrace
  "Wrap a handler such that errors are caught, logged, and a HTML response
  describing the error is returned.

  Accepts the following option:

  :logger - a function called with the error's stacktrace string (defaults
            to js/console.error)"
  ([handler]
   (wrap-stacktrace handler {}))
  ([handler options]
   (-> handler
       (wrap-stacktrace-log options)
       (wrap-stacktrace-web))))
