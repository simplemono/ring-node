# Ring-Node Spec (0.1.0)

Ring-node is an abstraction layer for building HTTP server applications in
ClojureScript on Node.js. It is derived from the Ring specification (1.5.1)
for Clojure on the JVM, adapted to the asynchronous, single-threaded nature
of the Node.js platform.

Where JVM Ring defines a synchronous API and an optional continuation-passing
asynchronous API, ring-node defines a single, promise-based API. There is no
synchronous API: handlers must never block the event loop.

## 1. Handlers

Ring handlers constitute the core logic of the web application. Handlers are
implemented as ClojureScript functions.

A handler takes 1 argument, a request map, and returns either a response map,
or a thenable (typically a `js/Promise`) that resolves to a response map.

```clojure
(fn [request] response)
```

```clojure
(defn ^:async handler [request]
  (let [data (await (query-database request))]
    {:status 200, :headers {}, :body (render data)}))
```

An error is signalled by throwing (in the synchronous prologue) or by
returning a thenable that rejects. Adapters must treat both identically.

## 2. Middleware

Ring middleware augment the functionality of handlers. Middleware is
implemented as higher-order functions that take one or more handlers and
configuration options as arguments and return a new handler with the desired
additional behavior.

Middleware must tolerate handlers that return either a plain response map or
a thenable of one. `(js/Promise.resolve result)` normalizes both.

## 3. Adapters

Ring adapters are side-effectful functions that take a handler and a map of
options as arguments, and when invoked start an HTTP server.

```clojure
(run-adapter handler options)
```

Since binding a server is asynchronous on Node.js, adapters should return a
`js/Promise` that resolves once the server is listening.

Once running, adapters receive HTTP requests, parse them to construct a
request map, and invoke their handler with this request map. When the
handler's return value resolves to a response map, the adapter uses it to
construct and send an HTTP response to the client. If it rejects, the adapter
must respond with a 500 response if the response head has not yet been sent,
and must otherwise terminate the connection.

## 4. Request Maps

A request map represents an HTTP request, and contains the following keys.
Any key not marked as **required** may be omitted.

| Key                 | Type                     | Required |
| ------------------- | ------------------------ | -------- |
|`:body`              |`stream.Readable`         |          |
|`:headers`           |`{String String}`         | Yes      |
|`:protocol`          |`String`                  | Yes      |
|`:query-string`      |`String`                  |          |
|`:remote-addr`       |`String`                  | Yes      |
|`:request-method`    |`Keyword`                 | Yes      |
|`:scheme`            |`Keyword`                 | Yes      |
|`:server-name`       |`String`                  | Yes      |
|`:server-port`       |`Integer`                 | Yes      |
|`:uri`               |`String`                  | Yes      |

The deprecated keys of the JVM spec (`:character-encoding`, `:content-length`,
`:content-type`) and the JVM-specific `:ssl-client-cert` are not part of this
specification.

#### :body

A Node.js `Readable` stream for the request body, if one is present. The
body arrives asynchronously; consumers must read it via stream events or a
helper such as `ring.util.request/body-string`, which returns a promise.
Reading the body consumes the stream.

#### :headers

A ClojureScript map of lowercased header name strings to corresponding
header value strings.

Where there are multiple headers with the same name, the adapter must
concatenate the values into a single string, using the ASCII `,` character
as a delimiter. The exception to this is the `cookie` header, which should
instead use the ASCII `;` character as a delimiter.

#### :protocol

The protocol the request was made with, e.g. "HTTP/1.1".

#### :query-string

The query segment of the URI in the HTTP request. This includes everything
after the `?` character, but excludes the `?` itself.

#### :remote-addr

The IP address of the client or the last proxy that sent the request.

#### :request-method

The HTTP request method. Must be a lowercase keyword corresponding to an HTTP
request method, such as `:get` or `:post`.

#### :scheme

The transport protocol denoted in the scheme of the request URL. Must be
either: `:http`, `:https`, `:ws` or `:wss`.

#### :server-name

The resolved server name, or the server IP address, as a string.

#### :server-port

The port on which the request is being handled.

#### :uri

The absolute path of the URI in the HTTP request. Must start with a `/`.

## 5. Response Maps

A response map represents an HTTP response, and contains the following keys.
Any key not marked as **required** may be omitted.

| Key      | Type                                       | Required |
| -------- | ------------------------------------------ | -------- |
|`:body`   |`ring.core.protocols/StreamableResponseBody`|          |
|`:headers`|`{String String}` or `{String [String]}`    | Yes      |
|`:status` |`Integer`                                   | Yes      |

#### :body

A representation of the response body that must satisfy the
`ring.core.protocols/StreamableResponseBody` protocol.

```clojure
(defprotocol StreamableResponseBody
  (write-body-to-stream [body response output-stream]))
```

The `response` argument is the full response map, and the `output-stream`
argument is a Node.js `Writable` stream. Implementations must return a
`js/Promise` that resolves once the body has been completely written and the
stream ended, and rejects if writing fails. Implementations must not close
the stream on error, so that the adapter can potentially send extra error
information to the client.

The `ring.core.protocols` namespace provides default implementations for the
following types:

* `String`
* `js/Buffer`
* `stream.Readable`
* ClojureScript sequences (each element is written as a string)
* `nil`

#### :headers

A ClojureScript map of header name strings to either a string or a vector of
strings that correspond to the header value or values.

#### :status

The HTTP status code. Must be greater than or equal to 100, and less than or
equal to 599.

## 6. Websockets

An HTTP request can be promoted into a websocket by means of an "upgrade"
header. In this situation, a handler may choose to return a websocket
response instead of an HTTP response — directly or via a thenable, like any
other response.

### 6.1. Websocket Responses

A websocket response is a map that represents a WebSocket, and may be
returned from a handler in place of a response map.

```clojure
(fn [request]
  #:ring.websocket{:listener websocket-listener})
```

A websocket response contains the following keys. Any key not marked as
**required** may be omitted.

| Key                      | Type                              | Required |
| ------------------------ | --------------------------------- | -------- |
|`:ring.websocket/listener`|`ring.websocket.protocols/Listener`| Yes      |
|`:ring.websocket/protocol`|`String`                           |          |

#### :ring.websocket/listener

An event listener that satisfies the `ring.websocket.protocols/Listener`
protocol, as described in section 6.2.

#### :ring.websocket/protocol

An optional websocket subprotocol. Must be one of the values listed in the
`Sec-Websocket-Protocol` header on the request.

### 6.2. Websocket Listeners

A websocket listener must satisfy the
`ring.websocket.protocols/Listener` protocol:

```clojure
(defprotocol Listener
  (on-open    [listener socket])
  (on-message [listener socket message])
  (on-pong    [listener socket data])
  (on-error   [listener socket error])
  (on-close   [listener socket code reason]))
```

It *may* optionally satisfy the `ring.websocket.protocols/PingListener`
protocol:

```clojure
(defprotocol PingListener
  (on-ping [listener socket data]))
```

If the `PingListener` protocol is not satisfied, the adapter *must* default
to responding to each ping message with a corresponding pong message that has
the same data.

#### on-open

Called once when the websocket is *successfully* opened. Supplies a `socket`
argument that satisfies `ring.websocket.protocols/Socket`, described in
section 6.3.

#### on-message

Called when a text or binary message frame is received from the client. The
`message` argument is a `String` for text messages, or a `js/Buffer` for
binary messages.

#### on-ping

Called when a "ping" frame is received from the client. The `data` argument
is a `js/Buffer` that contains optional client session data. If the user
implements this method, they are responsible for sending the return "pong"
that the websocket protocol expects.

#### on-pong

Called when a "pong" frame is received from the client. The `data` argument
is a `js/Buffer` that contains optional client session data.

#### on-error

Called when an error occurs. This may cause the websocket to be closed. The
`error` argument is a `js/Error`.

#### on-close

Called once when the websocket is closed, either via a valid close frame or
by an abnormal disconnect of the underlying TCP connection. Guaranteed to be
called if and only if `on-open` was called, so may be used for
finalizing/cleanup logic. Takes an integer `code` and a string `reason` as
arguments.

### 6.3. Websocket Sockets

A socket must satisfy the `ring.websocket.protocols/Socket` protocol:

```clojure
(defprotocol Socket
  (-open? [socket])
  (-send  [socket message])
  (-ping  [socket data])
  (-pong  [socket data])
  (-close [socket code reason]))
```

The JVM spec's separate `AsyncSocket` protocol does not exist in ring-node:
there is no blocking send on Node.js, so `-send`, `-ping`, `-pong` and
`-close` are themselves asynchronous and return promises.

#### -open?

Returns a truthy or falsey value denoting whether the socket is currently
connected to the client.

#### -send

Sends a websocket message frame that may be a `String` (for text) or a
`js/Buffer` (for binary). Returns a `js/Promise` that resolves when the frame
has been written, and rejects with a `js/Error` on failure.

#### -ping

Sends a websocket ping frame with a `js/Buffer` of session data (which may be
empty). Returns a `js/Promise`.

#### -pong

Sends an unsolicited pong frame with a `js/Buffer` of session data (which may
be empty). Returns a `js/Promise`.

#### -close

Closes the websocket with the supplied integer code and reason string.
Returns a `js/Promise` that resolves when the socket has closed.
