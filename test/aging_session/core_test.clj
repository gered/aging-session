(ns aging-session.core-test
  (:require
    [clojure.test :refer :all]
    [ring.middleware.session.store :refer :all]
    [aging-session.core :refer :all]))

(defn ->basic-aging-memory-store
  [& [opts]]
  (aging-memory-store
    30
    (merge
      {:refresh-on-read?  true
       :refresh-on-write? true
       :sweep-interval    15}
      opts)))

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
    (let [as (->basic-aging-memory-store
               {:refresh-on-read?  false
                :refresh-on-write? false})]
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
    (let [as (->basic-aging-memory-store
               {:refresh-on-read?  false
                :refresh-on-write? true})]
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
    (let [as (->basic-aging-memory-store
               {:refresh-on-read?  true
                :refresh-on-write? false})]
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

(deftest individual-session-entries-are-expired-when-read
  (testing "Individual session entries are expired appropriately when read, independently of the sweep thread."
    (let [as (aging-memory-store
               1                                            ; expire after 1 second
               {:sweep-interval  10                         ; sweeper thread tries to run every 10 seconds
                })]
      (write-session as "mykey" {:foo 1})
      (is (= (read-session as "mykey") {:foo 1})
          "session entry was written")
      (Thread/sleep 1500)                                   ; little delay, but too short for sweeper thread to have run
      (is (nil? (read-session as "mykey"))
          "session entry should no longer be present"))))

(deftest sweeper-thread-expires-entries-at-interval
  (testing "Sweeper thread expires entries whenever it runs."
    (let [as (aging-memory-store
               1                                            ; expire after 1 second
               {:refresh-on-read?  true
                :refresh-on-write? true
                :sweep-interval    1                         ; sweeper thread tries to run every 1 second
                })]
      (write-session as "mykey" {:foo 1})
      (Thread/sleep 20)
      (is (integer? (read-timestamp as "mykey"))
          "session entry should still be present")

      ; do nothing in the mean time (no write operations)

      (Thread/sleep 2000)                                   ; wait long enough for session ttl to elapse
      (is (nil? (read-timestamp as "mykey"))
          "session entry should have been removed now")

      ; now lets do this again, but read the value (thus, triggering a timestamp refresh) a couple times

      (is (nil? (read-session as "mykey"))
          "session entry should not be there yet")
      (write-session as "mykey" {:foo 1})
      (is (= (read-session as "mykey") {:foo 1})
          "session entry should now be present")

      ; repeatedly re-read the session for a period of time over the session store ttl so as to not let
      ; it expire (because refresh-on-read is enabled)
      (doseq [_ (range 10)]
        (Thread/sleep 200)
        (is (= (read-session as "mykey") {:foo 1})
            "session entry should still be present"))

      ; now wait long enough without reading to let it expire

      (Thread/sleep 2000)
      (is (nil? (read-session as "mykey"))
          "session entry should be gone now")

      )))

(deftest refresh-on-read-nonexistent-key-then-sweep
  (testing "Test an empty session read (with refresh-on-read enabled) then check that the expiry sweep still works"
    (let [as (aging-memory-store
               1                                            ; expire after 1 second
               {:refresh-on-read? true
                :sweep-interval   1                          ; sweep thread tries to run every 1 second
                })]
      (is (nil? (read-session as "foo"))
          "no session entry present for this key")
      (Thread/sleep 1500)
      ; read again to trigger the sweep
      (is (nil? (read-session as "foo"))
          "still no session entry present for this key"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sweeper-thread-can-be-stopped
  (let [as (->basic-aging-memory-store)]
    (Thread/sleep 2000)
    (is (.isAlive ^Thread (:thread as))
        "sweeper thread is currently alive")
    (stop as)
    (Thread/sleep 1000)
    (is (not (.isAlive ^Thread (:thread as)))
        "sweeper thread is no longer alive")))

(deftest can-get-all-sessions
  (let [as (->basic-aging-memory-store)]
    (write-session as "a" {:foo 1})
    (write-session as "b" {:bar 2})
    (let [sessions (all-entries as)]
      (is (= 2 (count sessions)))
      (is (= (get-in sessions ["a" :value]) {:foo 1}))
      (is (= (get-in sessions ["b" :value]) {:bar 2})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest removal-listener-triggered-when-read-session-expires-entry
  (let [removed (atom nil)
        as      (aging-memory-store 1 {:on-removal #(reset! removed [%1 %2 %3])})]
    (testing "before ttl elapses"
      (write-session as "foo" {:foo 1})
      (is (= (read-session as "foo") {:foo 1}))
      (is (nil? @removed)))
    (Thread/sleep 1500)
    (testing "after ttl has elapsed"
      (is (nil? @removed))
      (is (nil? (read-session as "foo")))
      (is (= ["foo" {:foo 1} :expired] @removed)))))

(deftest removal-listener-not-triggered-for-other-read-sessions-even-with-an-expired-entry
  (let [removed (atom nil)
        as      (aging-memory-store 1 {:on-removal #(reset! removed [%1 %2 %3])})]
    (testing "before ttl elapses"
      (write-session as "foo" {:foo 1})
      (write-session as "bar" {:bar 1})
      (is (= (read-session as "foo") {:foo 1}))
      (is (= (read-session as "bar") {:bar 1}))
      (is (nil? @removed)))
    (testing "delaying while keeping the second entry alive, long enough for the first entry to expire"
      (Thread/sleep 400)
      (is (= (read-session as "bar") {:bar 1}))
      (is (nil? @removed))
      (Thread/sleep 400)
      (is (= (read-session as "bar") {:bar 1}))
      (is (nil? @removed))
      (Thread/sleep 400)
      (is (= (read-session as "bar") {:bar 1}))
      (is (nil? @removed)))
    (testing "after ttl has elapsed"
      (is (nil? @removed))
      (is (nil? (read-session as "foo")))
      (is (= (read-session as "bar") {:bar 1}))
      (is (= ["foo" {:foo 1} :expired] @removed)))))

(deftest removal-listener-triggered-when-write-session-overwrites-expired-entry
  (let [removed (atom nil)
        as      (aging-memory-store 1 {:on-removal #(reset! removed [%1 %2 %3])})]
    (testing "before ttl elapses"
      (write-session as "foo" {:foo 1})
      (is (= (read-session as "foo") {:foo 1}))
      (is (nil? @removed)))
    (Thread/sleep 1500)
    (testing "after ttl has elapsed"
      (is (nil? @removed))
      (write-session as "foo" {:foo 2})
      (is (= (read-session as "foo") {:foo 2}))
      (is (= ["foo" {:foo 1} :expired] @removed)))))

(deftest sweeper-thread-triggers-removal-listeners-for-all-expired-entries
  (let [removed (atom {})
        as      (aging-memory-store 1 {:sweep-interval 1
                                       :on-removal     #(swap! removed assoc %1 {:timestamp (System/currentTimeMillis)
                                                                                 :value     %2
                                                                                 :reason    %3})})]
    (testing "before ttl elapses or sweeper thread runs"
      (write-session as "foo" {:foo 1})
      (write-session as "bar" {:bar 1})
      (write-session as "keep" {:keep 1})
      (is (= (read-session as "foo") {:foo 1}))
      (is (= (read-session as "bar") {:bar 1}))
      (is (= (read-session as "keep") {:keep 1}))
      (is (empty? @removed)))
    (testing "delaying while keeping 1 entry alive, long enough for the rest to expire and sweeper thread to run"
      (Thread/sleep 500)
      (is (= (read-session as "keep") {:keep 1}))
      (Thread/sleep 500)
      (is (= (read-session as "keep") {:keep 1}))
      (Thread/sleep 3000))
    (testing "after ttl elapses and sweeper thread has had enough time to run at least twice"
      (is (= 3 (count @removed)))
      (is (every? #(= :expired (:reason %)) (vals @removed)))
      (let [foo-bar-time-diff (Math/abs (- (:timestamp (get @removed "foo"))
                                           (:timestamp (get @removed "bar"))))
            keep-time-diff    (- (:timestamp (get @removed "keep"))
                                 (:timestamp (get @removed "bar")))]
        (testing "'foo' and 'bar' should have expired at roughly the same time. 'keep' at the next sweep interval.")
        (is (<= foo-bar-time-diff 200))                     ; probably overly generous, but less than one sweep-interval
        (is (>= keep-time-diff 800))))
    (stop as)))

(deftest removal-listener-triggered-when-delete-session-removes-entry
  (let [removed (atom nil)
        as      (aging-memory-store 1 {:on-removal #(reset! removed [%1 %2 %3])})]
    (write-session as "foo" {:foo 1})
    (is (= (read-session as "foo") {:foo 1}))
    (is (nil? @removed))
    (delete-session as "foo")
    (is (= ["foo" {:foo 1} :deleted] @removed))
    (is (nil? (read-session as "foo")))))

(deftest removal-listener-not-triggered-when-delete-session-called-for-non-existent-key
  (let [removed (atom nil)
        as      (aging-memory-store 1 {:on-removal #(reset! removed [%1 %2 %3])})]
    (delete-session as "foo")
    (is (nil? @removed))))

#_(run-tests)
