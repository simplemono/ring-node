(ns ring.middleware.session
  "Middleware for maintaining browser sessions using cookies.

  Sessions are stored using types that adhere to the
  ring.middleware.session.store/SessionStore protocol, whose methods return
  js/Promises. Ring-node comes with one store included:

    ring.middleware.session.memory/memory-store"
  (:require [ring.middleware.cookies :as cookies]
            [ring.middleware.session.memory :as mem]
            [ring.middleware.session.store :as store]
            [ring.util.async :as async]))

(defn- session-options
  [options]
  {:store        (options :store (mem/memory-store))
   :set-cookies? (options :set-cookies? true)
   :cookie-name  (options :cookie-name "ring-session")
   :cookie-attrs (merge {:path "/"
                         :http-only true}
                        (options :cookie-attrs)
                        (when-let [root (options :root)]
                          {:path root}))})

(defn- ^:async bare-session-request
  [request {:keys [store cookie-name]}]
  (let [req-key     (get-in request [:cookies cookie-name :value])
        session     (await (store/read-session store req-key))
        session-key (when session req-key)]
    (merge request {:session (or session {})
                    :session/key session-key})))

(defn session-request
  "Reads current HTTP session map and adds it to :session key of the request.
  Returns a js/Promise of the updated request. See: wrap-session."
  ([request]
   (session-request request {}))
  ([request options]
   (-> request
       cookies/cookies-request
       (bare-session-request options))))

(defn- ^:async bare-session-response
  [response {session-key :session/key} {:keys [store cookie-name cookie-attrs]}]
  (let [new-session-key
        (when (contains? response :session)
          (if-let [session (response :session)]
            (if (:recreate (meta session))
              (do
                (await (store/delete-session store session-key))
                (await (store/write-session
                        store nil (vary-meta session dissoc :recreate))))
              (await (store/write-session store session-key session)))
            (when session-key
              (await (store/delete-session store session-key)))))
        session-attrs (:session-cookie-attrs response)
        cookie {cookie-name
                (merge cookie-attrs
                       session-attrs
                       {:value (or new-session-key session-key)})}
        response (dissoc response :session :session-cookie-attrs)]
    (if (or (and new-session-key (not= session-key new-session-key))
            (and session-attrs (or new-session-key session-key)))
      (assoc response :cookies (merge (response :cookies) cookie))
      response)))

(defn session-response
  "Updates session based on :session key in response. Returns a js/Promise of
  the updated response, or nil if the response is nil. See: wrap-session."
  ([response request]
   (session-response response request {}))
  ([response request options]
   (when response
     (.then (bare-session-response response request options)
            (fn [response]
              (cond-> response
                (:set-cookies? options true) cookies/cookies-response))))))

(defn wrap-session
  "Reads in the current HTTP session map, and adds it to the :session key on
  the request. If a :session key is added to the response by the handler, the
  session is updated with the new value. If the value is nil, the session is
  deleted.

  Accepts the following options:

  :store        - An implementation of the SessionStore protocol in the
                  ring.middleware.session.store namespace. This determines how
                  the session is stored. Defaults to in-memory storage using
                  ring.middleware.session.memory/memory-store.

  :root         - The root path of the session. Any path above this will not be
                  able to see this session. Equivalent to setting the cookie's
                  path attribute. Defaults to \"/\".

  :cookie-name  - The name of the cookie that holds the session key. Defaults to
                  \"ring-session\".

  :cookie-attrs - A map of attributes to associate with the session cookie.
                  Defaults to {:http-only true}. This may be overridden on a
                  per-response basis by adding :session-cookie-attrs to the
                  response.

  :set-cookies? - If true, automatically include cookie middleware. Defaults to
                  true for backward compatibility."
  ([handler]
   (wrap-session handler {}))
  ([handler options]
   (let [options (session-options options)]
     (fn [request]
       (.then (session-request request options)
              (fn [request]
                (async/then (async/call handler request)
                            (fn [response]
                              (session-response response request options)))))))))
