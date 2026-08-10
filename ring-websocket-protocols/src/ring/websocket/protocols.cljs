(ns ring.websocket.protocols)

(defprotocol Listener
  "A protocol for handling websocket events. The second argument is always an
  object that satisfies the Socket protocol."
  (on-open [listener socket]
    "Called when the websocket is opened.")
  (on-message [listener socket message]
    "Called when a message is received. The message is a string for text
    frames, or a js/Buffer for binary frames.")
  (on-pong [listener socket data]
    "Called when a pong is received in response to an earlier ping. The
    client may provide additional binary data, represented by a js/Buffer.")
  (on-error [listener socket error]
    "Called when a js/Error is raised.")
  (on-close [listener socket code reason]
    "Called when the websocket is closed, along with an integer code and a
    plaintext string reason for being closed."))

(defprotocol PingListener
  "A protocol for handling ping websocket events. The second argument is
  always an object that satisfies the Socket protocol. This is separate from
  the Listener protocol for parity with JVM Ring; if it is not satisfied,
  the adapter responds to each ping with a corresponding pong carrying the
  same data."
  (on-ping [listener socket data]
    "Called when a ping is received from the client. The client may provide
    additional binary data, represented by a js/Buffer."))

(defprotocol Socket
  "A protocol for sending data via websocket. All sending methods return a
  js/Promise that resolves on completion and rejects with a js/Error on
  failure. This subsumes JVM Ring's AsyncSocket protocol: there is no
  blocking send on Node.js."
  (-open? [socket]
    "Returns true if the socket is open; false otherwise.")
  (-send [socket message]
    "Sends a string (text frame) or js/Buffer (binary frame) to the client
    via the websocket. Returns a js/Promise.")
  (-ping [socket data]
    "Sends a ping message to the client with a js/Buffer of extra data.
    Returns a js/Promise.")
  (-pong [socket data]
    "Sends an unsolicited pong message to the client, with a js/Buffer of
    extra data. Returns a js/Promise.")
  (-close [socket code reason]
    "Closes the socket with an integer status code and a string reason.
    Returns a js/Promise that resolves when the socket has closed."))
