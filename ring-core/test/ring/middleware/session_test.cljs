(ns ring.middleware.session-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :refer [memory-store]]
            [ring.middleware.session.store :as store]))

(defn- session-cookie [response]
  (some->> (get-in response [:headers "Set-Cookie"])
           (filter #(re-find #"^ring-session=" %))
           first))

(defn- cookie-value [cookie]
  (second (re-find #"^ring-session=([^;]*)" cookie)))

(deftest ^:async session-is-read-from-store
  (let [session-atom (atom {"test-key" {:user "alice"}})
        handler      (wrap-session
                      (fn [req] {:status 200 :headers {}
                                 :session-seen (:session req)})
                      {:store (memory-store session-atom)})
        resp         (await (handler {:headers {"cookie" "ring-session=test-key"}}))]
    (is (= {:user "alice"} (:session-seen resp)))))

(deftest ^:async new-session-is-written-to-store
  (let [session-atom (atom {})
        handler      (wrap-session
                      (constantly {:status 200 :headers {}
                                   :session {:user "bob"}})
                      {:store (memory-store session-atom)})
        resp         (await (handler {:headers {}}))
        cookie       (session-cookie resp)]
    (is (some? cookie) "a session cookie is set")
    (is (re-find #"; Path=/" cookie))
    (is (re-find #"; HttpOnly" cookie))
    (let [key (cookie-value cookie)]
      (is (= {:user "bob"} (get @session-atom key))))))

(deftest ^:async session-is-deleted-when-nil
  (let [session-atom (atom {"test-key" {:user "alice"}})
        handler      (wrap-session
                      (constantly {:status 200 :headers {} :session nil})
                      {:store (memory-store session-atom)})]
    (await (handler {:headers {"cookie" "ring-session=test-key"}}))
    (is (= {} @session-atom))))

(deftest ^:async session-key-unchanged-when-updating
  (let [session-atom (atom {"test-key" {:n 1}})
        handler      (wrap-session
                      (constantly {:status 200 :headers {} :session {:n 2}})
                      {:store (memory-store session-atom)})
        resp         (await (handler {:headers {"cookie" "ring-session=test-key"}}))]
    (is (nil? (session-cookie resp))
        "no new cookie when the session key is unchanged")
    (is (= {:n 2} (get @session-atom "test-key")))))

(deftest ^:async session-recreate-generates-new-key
  (let [session-atom (atom {"test-key" {:user "alice"}})
        handler      (wrap-session
                      (constantly {:status 200 :headers {}
                                   :session ^:recreate {:user "alice"}})
                      {:store (memory-store session-atom)})
        resp         (await (handler {:headers {"cookie" "ring-session=test-key"}}))
        new-key      (some-> resp session-cookie cookie-value)]
    (is (some? new-key))
    (is (not= "test-key" new-key))
    (is (nil? (get @session-atom "test-key")))
    (is (= {:user "alice"} (get @session-atom new-key)))))

(deftest ^:async session-untouched-response-passes-through
  (let [handler (wrap-session (constantly {:status 200 :headers {} :body "ok"}))
        resp    (await (handler {:headers {}}))]
    (is (= 200 (:status resp)))
    (is (nil? (session-cookie resp)))))

(deftest ^:async custom-cookie-name-and-attrs
  (let [handler (wrap-session
                 (constantly {:status 200 :headers {} :session {:a 1}})
                 {:cookie-name "custom"
                  :cookie-attrs {:secure true}})
        resp    (await (handler {:headers {}}))
        cookies (get-in resp [:headers "Set-Cookie"])]
    (is (some #(re-find #"^custom=" %) cookies))
    (is (some #(re-find #"; Secure" %) cookies))))

(deftest ^:async promise-based-custom-store
  ;; A store that satisfies the protocol with genuinely deferred promises.
  (let [data    (atom {})
        deferred (fn [v] (js/Promise. (fn [resolve _]
                                        (js/setTimeout #(resolve v) 1))))
        store   (reify store/SessionStore
                  (read-session [_ key]
                    (.then (deferred nil) (fn [_] (get @data key))))
                  (write-session [_ key session]
                    (let [key (or key "generated-key")]
                      (swap! data assoc key session)
                      (deferred key)))
                  (delete-session [_ key]
                    (swap! data dissoc key)
                    (deferred nil)))
        handler (wrap-session
                 (constantly {:status 200 :headers {} :session {:x 1}})
                 {:store store})
        resp    (await (handler {:headers {}}))]
    (is (= {"generated-key" {:x 1}} @data))
    (is (some? (session-cookie resp)))))
