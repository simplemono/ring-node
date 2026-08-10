(ns ring.util.async
  "Helpers for working with handlers that may return response maps or
  thenables of response maps.")

(defn call
  "Invoke a handler with a request, returning a js/Promise of the response.
  Synchronous throws become rejections, and plain return values become
  resolved promises."
  [handler request]
  (try
    (js/Promise.resolve (handler request))
    (catch :default e
      (js/Promise.reject e))))

(defn then
  "Call f with the resolved value of x, where x may be a plain value or a
  thenable. Returns a js/Promise."
  [x f]
  (.then (js/Promise.resolve x) f))
