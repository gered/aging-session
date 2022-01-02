(ns aging-session.memory_test
  (:require
    [clojure.test :refer :all]
    [ring.middleware.session.store :refer :all]
    [aging-session.memory :refer :all]))

(deftest basic-read-empty
  (testing "Test session reads when there is no session value for that key."
    (let [as (aging-memory-store)]
      (is (nil? (read-session as "mykey"))
          "returns nil for non-existent session read"))))

(deftest basic-write
  (testing "Test session writes and reads."
    (let [as (aging-memory-store)]
      (write-session as "mykey" {:a 1})
      (is (= (read-session as "mykey") {:a 1})
          "session value was written")
      (write-session as "mykey" {:a 2})
      (is (= (read-session as "mykey") {:a 2})
          "session value was updated"))))

(deftest basic-delete
  (testing "Test session delete."
    (let [as (aging-memory-store)]
      (write-session as "mykey" {:a 1})
      (is (= (read-session as "mykey") {:a 1})
          "session value was written")
      (delete-session as "mykey")
      (is (nil? (read-session as "mykey"))
          "session value is no longer present"))))

(deftest timestamp-on-creation
  (testing "Test the behaviour where each entry's timestamp is set only on session creation."
    (let [as (aging-memory-store)]
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
    (let [as (aging-memory-store :refresh-on-write true)]
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
    (let [as (aging-memory-store :refresh-on-read true)]
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

#_(run-tests)
