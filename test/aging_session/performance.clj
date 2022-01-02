(ns aging-session.performance
  (:require
    [clojure.test :refer :all]
    [criterium.core :refer [quick-bench]]
    [ring.middleware.session.store :refer :all]
    [ring.middleware.session.memory :refer [memory-store]]
    [aging-session.memory :refer [aging-memory-store]]))

; these are copied from ring-ttl-session's benchmarks so that i can see how the performance of
; aging-session compares against it.
; https://github.com/boechat107/ring-ttl-session/blob/develop/test/ring_ttl_session/performance.clj
;
; right now aging-session is slower than ExpiringMap (to be expected, honestly...), but generally faster than
; ring-ttl-session's core.cache implementation. but there are still things that we can do to speed up aging-session.

(defn ->aging-memory-store
  [ttl]
  (aging-memory-store
    ttl
    {:refresh-on-write true
     :refresh-on-read  true}))

(defn check-nonexistent-read
  []
  (let [aging (->aging-memory-store 10)
        mem   (memory-store)]
    (println \newline "*** AGING-SESSION ***" \newline)
    (quick-bench (read-session aging "nonexistent"))
    (println \newline "*** MEMORY-STORE ***" \newline)
    (quick-bench (read-session mem "nonexistent"))))

#_(check-nonexistent-read)

(defn check-session-read
  []
  (let [aging     (->aging-memory-store 10)
        mem       (memory-store)
        data      {:foo "bar"}
        aging-key (write-session aging nil data)
        mem-key   (write-session mem nil data)]
    (println \newline "*** AGING-SESSION ***" \newline)
    (quick-bench (read-session aging aging-key))
    (println \newline "*** MEMORY-STORE ***" \newline)
    (quick-bench (read-session mem mem-key))))

#_(check-session-read)

(defn check-session-create []
  (let [aging (->aging-memory-store 10)
        mem   (memory-store)
        data  {:foo "bar"}]
    (println \newline "*** AGING-SESSION ***" \newline)
    (quick-bench (write-session aging nil data))
    (println \newline "*** MEMORY-STORE ***" \newline)
    (quick-bench (write-session mem nil data))))

#_(check-session-create)
