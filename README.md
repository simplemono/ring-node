# ring-node

A port of [Ring](https://github.com/ring-clojure/ring) to **ClojureScript on
Node.js**, with a single promise-based handler contract built on native
async/await.

```clojure
(ns example.server
  (:require [ring.adapter.node :as node]
            [ring.middleware.params :refer [wrap-params]]))

(defn ^:async handler [request]
  (let [name (get-in request [:params "name"] "World")]
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (str "Hello " name)}))

(defn ^:async -main []
  (await (node/run-server (wrap-params handler) {:port 3000}))
  (println "Listening on http://localhost:3000"))
```

A handler takes a request map and returns a response map **or a thenable of
one**. Errors propagate as promise rejections. See [SPEC.md](SPEC.md) for the
normative contract; it is derived from the Ring spec with the sync/CPS split
replaced by promises, `:body` as a Node.js `Readable`, and websockets backed
by [`ws`](https://github.com/websockets/ws).

## Requirements

- **ClojureScript ≥ 1.12.145** — the sources use native `^:async` / `await`.
  (Declared as a hard dependency; tools.deps' newest-wins resolution enforces
  the minimum automatically.)
- **Node.js ≥ 20** (CI runs current LTS releases).
- Modules that need npm packages declare them in `deps.cljs` (`:npm-deps`);
  shadow-cljs picks these up automatically. With vanilla `cljs.main`, run
  `npm install ws` yourself for the adapter.

Because `^:async` is a compiler feature, these sources are **not** loadable
in self-hosted ClojureScript (nbb/SCI).

## Installation

ring-node is distributed exclusively as a **git dependency**; there are no
Maven artifacts. Depend on the module you need with `:deps/root`:

```clojure
{:deps
 {io.github.simplemono/ring-node
  {:git/url   "https://github.com/simplemono/ring-node.git"
   :git/tag   "0.1.0"
   :git/sha   "0000000"
   :deps/root "ring-core"}
  io.github.simplemono/ring-node-adapter
  {:git/url   "https://github.com/simplemono/ring-node.git"
   :git/tag   "0.1.0"
   :git/sha   "0000000"
   :deps/root "ring-node-adapter"}}}
```

## Modules

| Module                     | Contents                                                        |
| -------------------------- | --------------------------------------------------------------- |
| `ring-core-protocols`      | `StreamableResponseBody` over Node.js Writable streams          |
| `ring-websocket-protocols` | Websocket `Listener` / `PingListener` / `Socket` protocols      |
| `ring-core`                | `ring.middleware.*`, `ring.util.*`, `ring.websocket`            |
| `ring-node-adapter`        | `ring.adapter.node` — `node:http` adapter with websockets       |
| `ring-devel`               | `wrap-stacktrace`, `wrap-lint`                                  |

Namespaces keep their upstream `ring.*` names, so ports of JVM Ring
applications are mostly mechanical.

## Differences from JVM Ring

- One handler arity: `(fn [request] response-or-thenable)`. The 3-arity CPS
  contract does not exist.
- `:body` on requests is a Node.js `Readable`;
  `ring.util.request/body-string` returns a promise.
- `StreamableResponseBody` implementations return a completion promise.
- `SessionStore` methods (`read-session`, `write-session`, `delete-session`)
  return promises.
- Websocket `Socket` send/ping/pong/close return promises (`AsyncSocket` is
  merged into `Socket`).
- Dropped: `wrap-resource` and resource/url responses (no runtime classpath
  on Node.js), `wrap-reload` (shadow-cljs watch does this), servlet modules,
  the encrypted cookie session store (for now), multipart (planned as a
  separate `ring-multipart` module wrapping busboy).

## Development

```sh
npm install
npx shadow-cljs compile test && node target/test/node-tests.js
```

The repo is a deps.edn monorepo; modules reference each other with relative
`:local/root` deps, which git-dep consumers resolve inside the repository.

## License

Distributed under the MIT License, as a derivative work of Ring.
Copyright © 2009-2026 Mark McGranaghan, James Reeves and contributors;
ring-node modifications © 2026 ring-node contributors. See [LICENSE](LICENSE).
