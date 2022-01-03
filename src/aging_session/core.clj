(ns aging-session.core
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

(defn- process-read-entry
  [session-map ttl key refresh-on-read?]
  ; call sweep-entry on this key first, to ensure this key is expired if needed BEFORE we allow its value to be read
  (let [session-map (sweep-entry session-map ttl key)]
    (if (and refresh-on-read?
             (contains? session-map key))
      (update session-map key assoc :timestamp (now))       ; note: performs faster than assoc-in
      session-map)))

(defn- process-write-entry
  [session-map key data refresh-on-write?]
  (if refresh-on-write?
    ; just blindly write a new entry if we're refreshing-on-write, as obviously in that case, we don't care what,
    ; if any, existing value and timestamp was there to begin with ...
    (assoc session-map key (new-entry data))
    ; when not refreshing-on-write, we only need to update the entry value if there is an existing entry. otherwise,
    ; it is of course a brand new entry
    (if (contains? session-map key)
      (update session-map key assoc :value data)            ; note: performs faster than assoc-in
      (assoc session-map key (new-entry data)))))

(defprotocol AgingStore
  (read-timestamp [store key]
    "Read a session from the store and return its timestamp. If no key exists, returns nil.")

  (all-entries [store]
    "Returns a map containing all entries currently in the session store."))

(defrecord MemoryAgingStore [session-atom thread ttl refresh-on-write refresh-on-read op-counter op-threshold]
  AgingStore
  (read-timestamp [_ key]
    (get-in @session-atom [key :timestamp]))

  (all-entries [_]
    @session-atom)

  SessionStore
  (read-session [_ key]
    (when (contains? @session-atom key)
      (let [session-map (swap! session-atom process-read-entry ttl key refresh-on-read)]
        (if (contains? session-map key)
          (-> session-map                                   ; note: performs faster than get-in
              (get key)
              (get :value))
          ; TODO: notify expiry listener about expired 'key' here
          ))))

  (write-session [_ key data]
    (let [key (or key (unique-id))]
      (if op-threshold
        (swap! op-counter inc))
      (swap! session-atom process-write-entry key data refresh-on-write)
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
        store          (MemoryAgingStore.
                         session-atom thread ttl refresh-on-write refresh-on-read op-counter sweep-threshold)]
    (.start thread)
    store))

(defn stop
  "Stops the aging-memory-store. Currently only provided as a convenience for applications that need to restart their
   web handler. This function should be used in such a case to stop the sweeper-thread. The vast majority of apps won't
   need to call this ever."
  [^MemoryAgingStore store]
  (if store
    (.interrupt ^Thread (.thread store))))
