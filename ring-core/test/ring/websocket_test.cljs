(ns ring.websocket-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as p]))

(deftest test-upgrade-request?
  (is (ws/upgrade-request? {:headers {"connection" "Upgrade"
                                      "upgrade" "websocket"}}))
  (is (ws/upgrade-request? {:headers {"connection" "keep-alive, upgrade"
                                      "upgrade" "WebSocket"}}))
  (is (not (ws/upgrade-request? {:headers {"connection" "close"
                                           "upgrade" "websocket"}})))
  (is (not (ws/upgrade-request? {:headers {"connection" "upgrade"}})))
  (is (not (ws/upgrade-request? {:headers {}}))))

(deftest test-websocket-response?
  (is (ws/websocket-response? {:ring.websocket/listener {}}))
  (is (not (ws/websocket-response? {:status 200 :headers {}}))))

(deftest test-request-protocols
  (is (= ["mqtt" "soap"]
         (ws/request-protocols
          {:headers {"sec-websocket-protocol" "mqtt, soap"}})))
  (is (nil? (ws/request-protocols {:headers {}}))))

(defrecord FakeSocket [calls open]
  p/Socket
  (-open? [_] open)
  (-send [_ message] (swap! calls conj [:send message]) (js/Promise.resolve nil))
  (-ping [_ data] (swap! calls conj [:ping data]) (js/Promise.resolve nil))
  (-pong [_ data] (swap! calls conj [:pong data]) (js/Promise.resolve nil))
  (-close [_ code reason] (swap! calls conj [:close code reason])
    (js/Promise.resolve nil)))

(deftest test-socket-wrappers
  (let [calls  (atom [])
        socket (->FakeSocket calls true)]
    (is (ws/open? socket))
    (ws/send socket "hi")
    (ws/ping socket)
    (ws/pong socket)
    (ws/close socket)
    (let [[[_ msg] [ping-op ping-data] [pong-op _] [close-op code reason]] @calls]
      (is (= "hi" msg))
      (is (= :ping ping-op))
      (is (zero? (.-length ping-data)) "default ping data is an empty Buffer")
      (is (= :pong pong-op))
      (is (= [:close 1000 "Normal Closure"] [close-op code reason])))))

(deftest ^:async test-send-with-callbacks
  (let [calls  (atom [])
        socket (->FakeSocket calls true)
        result (await (js/Promise.
                       (fn [resolve _]
                         (ws/send socket "msg" #(resolve :succeeded) #(resolve :failed)))))]
    (is (= :succeeded result))))

(deftest test-map-listener
  (testing "callbacks in maps are invoked"
    (let [seen   (atom [])
          m      {:on-open    (fn [s] (swap! seen conj [:open s]))
                  :on-message (fn [_ msg] (swap! seen conj [:message msg]))
                  :on-close   (fn [_ code reason] (swap! seen conj [:close code reason]))}
          socket (->FakeSocket (atom []) true)]
      (p/on-open m socket)
      (p/on-message m socket "hello")
      (p/on-error m socket (js/Error. "ignored"))   ;; missing key is a no-op
      (p/on-close m socket 1000 "bye")
      (is (= [[:open socket] [:message "hello"] [:close 1000 "bye"]] @seen))))

  (testing "maps satisfy PingListener with pong-by-default"
    (let [calls  (atom [])
          socket (->FakeSocket calls true)
          data   (js/Buffer.from "ping-data")]
      (p/on-ping {} socket data)
      (is (= [[:pong data]] @calls))))

  (testing "explicit :on-ping takes over"
    (let [calls  (atom [])
          socket (->FakeSocket calls true)
          pinged (atom nil)]
      (p/on-ping {:on-ping (fn [_ data] (reset! pinged data))}
                 socket (js/Buffer.from "x"))
      (is (some? @pinged))
      (is (= [] @calls) "no automatic pong when :on-ping is supplied"))))
