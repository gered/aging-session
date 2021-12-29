# aging-session

A memory based ring session store that has a concept of time. The primary goal
is to allow the session store to deallocate old sessions. While much of this
may be written on top of the standard ring session store, there is ultimately
no way to get rid of sessions that are no longer being visited.

Depending on how long running a server is and on how big its sessions are,
the unallocated sessions can potentially accumulate more and more memory.
Another possible scenario is a denial of service attack where the attacker
continually asks for new sessions thus exhusting the server of memory.

This session store has a sweeper thread that will apply a set of functions
to every session object after every X requests are made. These functions
are also applied to every session when it is read.

## Leiningen

```clj
[net.gered/aging-session "0.1.0"]
```

## This is a fork!

Here is the original [aging-session](https://github.com/kirasystems/aging-session) repository that this is forked from.
This fork is based off of version 0.3.1. Subsequent versions added extra complexity which I do not want in this fork.

### Rationale

The current version of aging-session by Kira Systems became somewhat complicated over the past few years with all these 
extra protocols and signed cookie support and more that I don't need or want in this library. I **just** need this 
library to help me manage session stores with sessions that are automatically expired and cleaned up in real-time. All 
the cookie stuff and all those extras, should I need them, can (and in my very-strongly-held opinion, _should_) be 
added on through other means, such as with other libraries providing that extra functionality.

As it stands the version of aging-session that is still maintained by Kira Systems feels a _little_ bit like it is 
too heavily influenced by the internal requirements of their own projects.

And that is _**totally**_ fine! :-) But it is not what I needed, thus I started this fork.

### What about alternative libraries?

The only real alternative library that I am aware of is [ring-ttl-session](https://github.com/luminus-framework/ring-ttl-session)
which is built on top of [ExpiringMap](https://github.com/jhalterman/expiringmap). For the most part this works well,
but `ExpiringMap` has a number of issues with thread-safety (a couple of which I have hit recently) that have been 
unresolved for a long while now. Which is unfortunate as it's otherwise a great looking library!

## Usage

The following creates a memory aging store that refreshes the timestamp every
time the session is written and erases entries after 1 hour.

```clojure
(ns myapp
  (:use 
    ring.middleware.session
    aging-session.memory)
  (:require ['aging-session.event :as event]))

(def app
  (wrap-session handler {:store (aging-memory-store 
                                  :refresh-on-write true
                                  :events           [(event/expires-after 3600)])}))
```

Event functions take two parameters: the current timestamp and a session entry
with a timestamp key and an value key. The timestamp key stores the sessions
timestamp and the value key stores the session itself. Functions should return
a new entry, or nil. If they return nil, the session entry is deleted. The
expires after function illustrates this.

```clojure
(defn expires-after
  "Expires an entry if left untouched for a given number of seconds."
  [seconds]
  (let [ms (* 1000 seconds)]
    (fn [now entry] (if-not (> (- now (:timestamp entry)) ms) entry))))
```

Event functions are applied in order and can be used to modify sessions in
any time-based way. For instance, one may wish to set a reauthentication flag
in sessions older than 1 hour, and delete sessions older than 2 hours.


## License

Copyright © 2012-2018 Kira Inc.

This library originally developed by Alexander Hudek (https://github.com/akhudek)

Various updates and other changes in this fork by Gered King (https://github.com/gered)

Distributed under the Eclipse Public License, the same as Clojure.
