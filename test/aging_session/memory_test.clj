(ns aging-session.memory_test
  (:require
    [clojure.test :refer :all]
    [ring.middleware.session.store :refer :all]
    [aging-session.memory :refer :all]))

(defn ->basic-aging-memory-store
  [& [opts]]
  (aging-memory-store 30 opts))

(deftest basic-read-empty
  (testing "Test session reads when there is no session value for that key."
    (let [as (->basic-aging-memory-store)]
      (is (nil? (read-session as "mykey"))
          "returns nil for non-existent session read"))))

(deftest basic-write
  (testing "Test session writes and reads."
    (let [as (->basic-aging-memory-store)]
      (write-session as "mykey" {:a 1})
      (is (= (read-session as "mykey") {:a 1})
          "session value was written")
      (write-session as "mykey" {:a 2})
      (is (= (read-session as "mykey") {:a 2})
          "session value was updated"))))

(deftest basic-delete
  (testing "Test session delete."
    (let [as (->basic-aging-memory-store)]
      (write-session as "mykey" {:a 1})
      (is (= (read-session as "mykey") {:a 1})
          "session value was written")
      (delete-session as "mykey")
      (is (nil? (read-session as "mykey"))
          "session value is no longer present"))))

(deftest timestamp-on-creation
  (testing "Test the behaviour where each entry's timestamp is set only on session creation."
    (let [as (->basic-aging-memory-store)]
      (write-session as "mykey" {:foo 1})
      (let [ts1 (read-timestamp as "mykey")]
        (is (integer? ts1)
            "timestamp was set on session write")
        (write-session as "mykey" {:foo 2})
        (Thread/sleep 10)
        (is (= ts1 (read-timestamp as "mykey"))
            "timestamp is unchanged for this session entry after it was updated")
        (is (= (read-session as "mykey") {:foo 2})
            "updated session entry value was written successfully")))))

(deftest timestamp-on-write-only
  (testing "Test the behaviour where each entry's timestamp is refreshed on write (not read)."
    (let [as (->basic-aging-memory-store {:refresh-on-write true})]
      (write-session as "mykey" {:foo 1})
      (let [ts1 (read-timestamp as "mykey")]
        (is (integer? ts1)
            "timestamp was set on session write")
        (is (= (read-session as "mykey") {:foo 1})
            "session value can be read")
        (Thread/sleep 10)
        (is (= ts1 (read-timestamp as "mykey"))
            "reading the session value did not update its timestamp")
        (write-session as "mykey" {:foo 2})
        (Thread/sleep 10)
        (is (not (= ts1 (read-timestamp as "mykey")))
            "timestamp of the session entry was updated after its value was updated")
        (is (= (read-session as "mykey") {:foo 2})
            "session value was updated successfully")))))

(deftest timestamp-on-read-only
  (testing "Test the behaviour where each entry's timestamp is refreshed on read (not write)."
    (let [as (->basic-aging-memory-store {:refresh-on-read true})]
      (write-session as "mykey" {:foo 1})
      (let [ts1 (read-timestamp as "mykey")]
        (is (integer? ts1)
            "timestamp was set on session write")
        (Thread/sleep 10)
        (is (= (read-session as "mykey") {:foo 1})
            "session value can be read")
        (let [ts2 (read-timestamp as "mykey")]
          (is (not (= ts1 ts2))
              "timestamp of the session entry was updated after its value was read")
          (is (= (read-session as "mykey") {:foo 1})
              "session value can still be read successfully")
          (Thread/sleep 10)
          (let [ts3 (read-timestamp as "mykey")]
            (write-session as "mykey" {:foo 2})
            (Thread/sleep 10)
            (is (= ts3 (read-timestamp as "mykey"))
                "timestamp of the session entry was not updated after its value was written")
            (is (= (read-session as "mykey") {:foo 2})
                "session value was updated successfully")
            (Thread/sleep 10)
            (is (not (= ts3 (read-timestamp as "mykey")))
                "timestamp of the session entry was updated after its new value was read")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest session-expiry
  (testing "Test session expiry."
    ; store where entries should expire after 1 second
    (let [as (aging-memory-store 1)]
      (write-session as "mykey" {:foo 1})
      (is (= (read-session as "mykey") {:foo 1})
          "session entry was written")
      (Thread/sleep 1500)
      (is (nil? (read-session as "mykey"))
          "session entry should no longer be present"))))

(deftest session-expiry-by-sweep
  (testing "Test session expiry sweep."
    (let [as (aging-memory-store
               1                                            ; expire after 1 second
               {:sweep-every 5                              ; only trigger sweep after 5 writes
                :sweep-delay 1000                           ; sweep thread tries to run every 1 second
                })]
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
               1                                            ; expire after 1 second
               {:refresh-on-read true
                :sweep-every     1                          ; sweep runs after every write
                :sweep-delay     1000                       ; sweep thread tries to run every 1 second
                })]
      (is (nil? (read-session as "foo"))
          "no session entry present for this key")
      (Thread/sleep 1500)
      ; read again to trigger the sweep
      (is (nil? (read-session as "foo"))
          "still no session entry present for this key"))))

#_(run-tests)
