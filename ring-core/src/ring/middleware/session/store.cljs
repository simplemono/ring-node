(ns ring.middleware.session.store
  "Contains the protocol used to define all Ring session storage engines.")

(defprotocol SessionStore
  "An interface to a session storage engine. Implementing this protocol allows
  Ring session data to be stored in different places. Unlike JVM Ring, all
  methods return js/Promises, so stores may be backed by asynchronous I/O.

  Session keys are exposed to end users via a cookie, and therefore must be
  unguessable. A random UUID is a good choice for a session key.

  Session stores should come with a mechanism for expiring old session data."
  (read-session [store key]
    "Read a session map from the store. Returns a js/Promise that resolves to
    the session map, or to nil if the key is not found.")
  (write-session [store key data]
    "Write a session map to the store. Returns a js/Promise that resolves to
    the (possibly changed) key under which the data was stored. If the key is
    nil, the session is considered to be new, and a fresh key should be
    generated.")
  (delete-session [store key]
    "Delete a session map from the store. Returns a js/Promise that resolves
    to the session key, or to nil if the session cookie should be removed."))
