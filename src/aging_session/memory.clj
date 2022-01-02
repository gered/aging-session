(ns aging-session.memory
  "In-memory session storage with mortality."
  (:require
    [ring.middleware.session.store :refer :all])
  (:import
    [java.util UUID]))

(defrecord SessionEntry [timestamp value])

(defn- now
  "Return the current time in milliseconds."
  []
  (System/currentTimeMillis))

(defn- new-entry
  "Create a new session entry for data."
  [data]
  (SessionEntry. (now) data))

(defn- write-entry
  "Write over an existing entry. If timestamp is missing, recreate."
  [session-map key data]
  (if (get-in session-map [key :timestamp])
    (assoc-in session-map [key :value] data)
    (assoc session-map key (new-entry data))))

(defn- update-entry
  "Update a session entry based on the configured session entry ttl."
  [ttl now [k {:keys [timestamp] :as v}]]
  (if v
    (if-not (> (- now timestamp) ttl)
      [k v])))

(defn- sweep-session
  "Sweep the session and run all session functions."
  [session-map now ttl]
  (into {} (keep #(update-entry ttl now %) session-map)))

(defn- sweep-entry
  "Sweep a single entry."
  [session-map now ttl key]
  (if-let [existing-entry (get session-map key)]
    (if-let [[_ entry] (update-entry ttl now [key existing-entry])]
      (assoc session-map key entry)
      (dissoc session-map key))
    session-map))

(defprotocol AgingStore
  (read-timestamp [store key]
    "Read a session from the store and return its timestamp. If no key exists, returns nil."))

(defrecord MemoryAgingStore [session-map ttl refresh-on-write refresh-on-read req-count req-limit]
  AgingStore
  (read-timestamp [_ key]
    (get-in @session-map [key :timestamp]))

  SessionStore
  (read-session [_ key]
    (when (contains? @session-map key)
      (let [ts (now)]
        (swap! session-map sweep-entry ts ttl key)
        (when (and refresh-on-read (contains? @session-map key))
          (swap! session-map assoc-in [key :timestamp] ts))
        (get-in @session-map [key :value]))))

  (write-session [_ key data]
    (let [key (or key (str (UUID/randomUUID)))]
      (swap! req-count inc)                                 ; Increase the request count
      (if refresh-on-write                                  ; Write key and and update timestamp.
        (swap! session-map assoc key (new-entry data))
        (swap! session-map write-entry key data))
      key))

  (delete-session [_ key]
    (swap! session-map dissoc key)
    nil))

(defn sweeper-thread
  "Sweeper thread that watches the session and cleans it."
  [{:keys [ttl req-count req-limit session-map]} sweep-delay]
  (loop []
    (when (>= @req-count req-limit)
      (swap! session-map sweep-session (now) ttl)
      (reset! req-count 0))
    (Thread/sleep sweep-delay)                              ;; sleep for 30s
    (recur)))

(defn in-thread
  "Run a function in a thread."
  [f]
  (.start (Thread. ^Runnable f)))

(defn aging-memory-store
  "Creates an in-memory session storage engine where entries expire after the given ttl"
  [ttl & [opts]]
  (let [{:keys [session-atom refresh-on-write refresh-on-read sweep-every sweep-delay]
         :or   {session-atom     (atom {})
                refresh-on-write false
                refresh-on-read  false
                sweep-every      200
                sweep-delay      30000}} opts
        ttl          (* 1000 ttl)                           ; internally, we want ttl in milliseconds for convenience...
        counter-atom (atom 0)
        store        (MemoryAgingStore. session-atom ttl refresh-on-write refresh-on-read counter-atom sweep-every)]
    (in-thread #(sweeper-thread store sweep-delay))
    store))

