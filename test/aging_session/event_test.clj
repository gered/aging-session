(ns aging-session.event_test
  (:require
    [aging-session.event :as event]
    [clojure.test :refer :all]
    [ring.middleware.session.store :refer :all]
    [aging-session.memory :refer :all]))

(deftest session-expiry
  (testing "Test session expiry."
    (let [as (aging-memory-store :events [(event/expires-after 1)])]
      (write-session as "mykey" {:foo 1})
      (. Thread (sleep 1500))
      (is (= (read-session as "mykey") {})))))

(deftest session-expiry-by-sweep
  (testing "Test session expiry sweep."
    (let [as (aging-memory-store
               :events      [(event/expires-after 1)]
               :sweep-every 5
               :sweep-delay 1000)]
      (write-session as "mykey" {:foo 1})
      (. Thread (sleep 1500))
      ; key should still exist, even though it's expired
      (is (not (nil? (read-timestamp as "mykey"))))

      ; key should exist for three more writes
      (write-session as "other-key" {:foo 1})
      (is (not (nil? (read-timestamp as "mykey"))))
      (write-session as "other-key" {:foo 1})
      (is (not (nil? (read-timestamp as "mykey"))))
      (write-session as "other-key" {:foo 1})
      (is (not (nil? (read-timestamp as "mykey"))))

      ; on the fifth write and after 30s, key should not exist
      (write-session as "other-key" {:foo 1})
      (. Thread (sleep 2000))
      (is (nil? (read-timestamp as "mykey"))))))

(deftest refresh-on-read-nonexistant-key-then-sweep
  (testing "Test an empty session read (with refresh-on-read enabled) then check that the expiry sweep still works"
    (let [as (aging-memory-store
               :events [(event/expires-after 1)]
               :refresh-on-read true
               :sweep-every 1
               :sweep-delay 1000)]
      (is (= (read-session as "foo") {}))
      ; read again to trigger the sweep
      (is (= (read-session as "foo") {})))))
