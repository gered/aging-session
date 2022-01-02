(ns aging-session.event_test
  (:require
    [clojure.test :refer :all]
    [ring.middleware.session.store :refer :all]
    [aging-session.event :as event]
    [aging-session.memory :refer :all]))

(deftest session-expiry
  (testing "Test session expiry."
    ; store where entries should expire after 1 second
    (let [as (aging-memory-store :events [(event/expires-after 1)])]
      (write-session as "mykey" {:foo 1})
      (is (= (read-session as "mykey") {:foo 1})
          "session entry was written")
      (Thread/sleep 1500)
      (is (nil? (read-session as "mykey"))
          "session entry should no longer be present"))))

(deftest session-expiry-by-sweep
  (testing "Test session expiry sweep."
    (let [as (aging-memory-store
               :events [(event/expires-after 1)]            ; expire after 1 second
               :sweep-every 5                               ; only trigger sweep after 5 writes
               :sweep-delay 1000                            ; sweep thread tries to run every 1 second
               )]
      (write-session as "mykey" {:foo 1})
      (Thread/sleep 1500)
      ; key should still exist, even though it's expired (not enough writes have occurred)
      (is (integer? (read-timestamp as "mykey"))
          "session entry should still be present even though it has expired")

      ; key should exist for three more writes
      (write-session as "other-key" {:foo 1})
      (is (integer? (read-timestamp as "mykey"))
          "session entry should still be present even though it has expired")
      (write-session as "other-key" {:foo 1})
      (is (integer? (read-timestamp as "mykey"))
          "session entry should still be present even though it has expired")
      (write-session as "other-key" {:foo 1})
      (is (integer? (read-timestamp as "mykey"))
          "session entry should still be present even though it has expired")

      ; on the fifth write and after 1 second, key should not exist
      (write-session as "other-key" {:foo 1})
      (Thread/sleep 2000)                                   ; allow time for sweeper thread to run
      (is (nil? (read-timestamp as "mykey"))
          "session entry should have been removed now"))))

(deftest refresh-on-read-nonexistant-key-then-sweep
  (testing "Test an empty session read (with refresh-on-read enabled) then check that the expiry sweep still works"
    (let [as (aging-memory-store
               :events [(event/expires-after 1)]            ; expire after 1 second
               :refresh-on-read true
               :sweep-every 1                               ; sweep runs after every write
               :sweep-delay 1000)                           ; sweep thread tries to run every 1 second
          ]
      (is (nil? (read-session as "foo"))
          "no session entry present for this key")
      (Thread/sleep 1500)
      ; read again to trigger the sweep
      (is (nil? (read-session as "foo"))
          "still no session entry present for this key"))))
