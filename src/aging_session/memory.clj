(ns aging-session.memory
  "In-memory session storage with mortality."
  (:require
    [ring.middleware.session.store :refer :all])
  (:import
    [java.util UUID]))

(defrecord SessionEntry [timestamp value])

(defn- unique-id
  "Returns a new unique value suitable for a session ID."
  []
  (str (UUID/randomUUID)))

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

(defn- entry-expired?
  "Returns true if the given session entry has expired according to its current timestamp and the session store's
   configured ttl"
  [ttl v]
  (and v
       (> (- (now) (:timestamp v))
          ttl)))

(defn- sweep-session
  "'Sweep' the session map, removing all entries whose lifetimes have exceeded the given ttl."
  [session-map ttl]
  (->> session-map
       (remove #(entry-expired? ttl (val %)))
       (into {})))

(defn- sweep-entry
  "'Sweep' a single entry, removing it from the session map if its lifetime has exceeded the given ttl."
  [session-map ttl key]
  (if-let [existing-entry (get session-map key)]
    (if-not (entry-expired? ttl existing-entry)
      (assoc session-map key existing-entry)
      (dissoc session-map key))
    session-map))

(defprotocol AgingStore
  (read-timestamp [store key]
    "Read a session from the store and return its timestamp. If no key exists, returns nil."))

(defrecord MemoryAgingStore [session-atom thread ttl refresh-on-write refresh-on-read op-counter op-threshold]
  AgingStore
  (read-timestamp [_ key]
    (get-in @session-atom [key :timestamp]))

  SessionStore
  (read-session [_ key]
    (when (contains? @session-atom key)
      (let []
        (swap! session-atom sweep-entry ttl key)
        (when (and refresh-on-read (contains? @session-atom key))
          (swap! session-atom assoc-in [key :timestamp] (now)))
        (get-in @session-atom [key :value]))))

  (write-session [_ key data]
    (let [key (or key (unique-id))]
      (if op-threshold
        (swap! op-counter inc))
      (if refresh-on-write
        (swap! session-atom assoc key (new-entry data))
        (swap! session-atom write-entry key data))
      key))

  (delete-session [_ key]
    (swap! session-atom dissoc key)
    nil))

(defn- sweeper-thread
  "Sweeper thread that watches the session and cleans it."
  [session-atom ttl op-counter op-threshold sweep-interval]
  (loop []
    (if op-threshold
      (when (>= @op-counter op-threshold)
        (swap! session-atom sweep-session ttl)
        (reset! op-counter 0))
      (swap! session-atom sweep-session ttl))
    (Thread/sleep sweep-interval)
    (recur)))

(defn aging-memory-store
  "Creates an in-memory session storage engine where entries expire after the given ttl"
  [ttl & [opts]]
  (let [{:keys [session-atom refresh-on-write refresh-on-read sweep-threshold sweep-interval]
         :or   {session-atom     (atom {})
                refresh-on-write true
                refresh-on-read  true
                sweep-threshold  nil
                sweep-interval   30}} opts
        ; internally, we want time values as milliseconds. externally, it is more convenient to have them specified
        ; as seconds because, really, for sessions, no one is really going to want to specify sub-second values for
        ; any of these times! (no, you don't really need a sweeper thread running multiple times per second ...)
        sweep-interval (* 1000 sweep-interval)
        ttl            (* 1000 ttl)
        op-counter     (if sweep-threshold (atom 0))
        thread         (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (sweeper-thread session-atom ttl op-counter sweep-threshold sweep-interval)
                             (catch InterruptedException e))))
        store          (MemoryAgingStore. session-atom thread ttl refresh-on-write refresh-on-read op-counter sweep-threshold)]
    (.start thread)
    store))

(defn stop
  "Stops the aging-memory-store. Currently only provided as a convenience for applications that need to restart their
   web handler. This function should be used in such a case to stop the sweeper-thread. The vast majority of apps won't
   need to call this ever."
  [^MemoryAgingStore store]
  (if store
    (.interrupt ^Thread (.thread store))))

