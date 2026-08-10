# Changelog

## 0.1.0 (unreleased)

Initial port of Ring to ClojureScript on Node.js, derived from Ring 1.15.5.

- Promise-based handler contract: a handler takes a request map and returns a
  response map or a thenable of one (see SPEC.md).
- `ring.core.protocols/StreamableResponseBody` writes to Node.js Writable
  streams and returns a completion promise.
- `ring.middleware.session.store/SessionStore` methods return promises.
- `ring.adapter.node` adapter over `node:http`, with websocket support backed
  by the `ws` npm package.
- Ported middleware: params, keyword-params, nested-params, content-type,
  not-modified, head, flash, cookies, session (memory store), file.
- Ported dev middleware: stacktrace, lint.
- Dropped (not portable or superseded on Node.js): resource/url responses and
  `wrap-resource` (no runtime classpath), `wrap-reload` (use shadow-cljs
  watch), servlet modules, `ring.middleware.session.cookie` (JVM crypto; may
  return later on node:crypto).
