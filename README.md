# aging-session

A memory based [Ring session store](https://github.com/ring-clojure/ring/wiki/Sessions#session-stores) that has a 
concept of time. The primary goal is to allow the session store to deallocate old sessions. While much of this may be 
written on top of the standard Ring session store, there is ultimately no way to get rid of sessions that are no longer
being visited.

Depending on how long running a server is and on how big its sessions are, the unallocated sessions can potentially 
accumulate more and more memory. Another possible scenario is a denial of service attack where the attacker continually
asks for new sessions thus exhausting the server of memory.

This session store has a sweeper thread that will apply time-based expiry to sessions being held in memory, so that
sessions are expired appropriately even if they are no longer being visited.

## Leiningen

[![](https://clojars.org/net.gered/aging-session/latest-version.svg)](https://clojars.org/net.gered/aging-session)

## This is a fork!

Here is the original [aging-session](https://github.com/kirasystems/aging-session) repository that this is forked from.
This fork is based off of version 0.3.1. Subsequent versions added extra complexity which I do not want in this fork.

I will be updating this repository with little tweaks and enhancements here and there, but for the most part intend
to keep it nice and simple as it was originally.

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

The only real alternative library that I am aware of at this time is [ring-ttl-session](https://github.com/luminus-framework/ring-ttl-session)
which is built on top of [ExpiringMap](https://github.com/jhalterman/expiringmap). For the most part this works well,
and if your web app is like most web apps out there and you're only ever interacting with your session store via the 
three methods found on Ring's `SessionStore` protocol (`read-session`, `write-session` and `delete-session`) then you
likely will be very well served with ring-ttl-session.

On the other hand, if you have more in-depth needs and do things such as inspect/iterate through the underlying session
map to find sessions matching certain criteria (for example), then `ExpiringMap`s unresolved thread-safety issues will 
very quickly become apparent and you will probably need to find an alternative solution.

## Usage

For the vast majority of applications, you can switch to aging-session's session store by replacing usage of Ring's
`memory-store` with aging-session's `aging-memory-store` and supplying a session expiry time (in seconds).

```clojure
(ns myapp
  (:require
    [aging-session.core :refer [aging-memory-store]]
    [ring.middleware.session :refer [wrap-session]]))

(def session-store
  (aging-memory-store 3600))

(def app
  (wrap-session handler {:store session-store}))
```

The default configuration used by `aging-memory-store` will result in a session store where session entries have their 
timestamp refreshed whenever read or written to. As well, the session store will have an associated "sweeper thread" 
which will run every 30 seconds across each session entry and remove expired sessions if needed.

You can adjust these defaults with an alternate configuration map supplied to `aging-memory-store`:

```clojure
(def session-store
  (aging-memory-store
    3600
    {:refresh-on-read?  true
     :refresh-on-write? true
     :sweep-interval    30}))

(def app
  (wrap-session handler {:store session-store}))
```

### Removal Listeners

You can also make use of "removal listeners" to get a notification whenever any session entry is expired or removed for
any reason. This can be useful in some applications which may need to perform actions to clean other things up, or to
notify the user's browser in realtime to let them know their session has expired (for example).

```clojure
(def session-store
  (aging-memory-store
    3600
    {:on-removal
     (fn [id data reason]
       (println (format "session removed because of %s. session-id: %s, data: %s"
                        reason id data)))}))

(def app
  (wrap-session handler {:store session-store}))
```

Currently the only two removal reasons that will occur are `:expired` (obviously only occurs as a result of a session
entry expiring due to age) and `:deleted` (only occurs as a result of the session being explicitly removed by the
server through a call to `delete-session`).

### Stopping the Session Store

Some applications will not need to care about this, but because aging-session's session store has an associated 
"sweeper thread" it is important to at least point this out so developers are aware of this.

If you ever have any reason in your application to restart your Ring handler (e.g. because you use something like
[Component](https://github.com/stuartsierra/component) or [Mount](https://github.com/tolitius/mount) and have your Ring
handler itself set up as a restartable component), then you should take care to _also_ define your aging-session store 
as a component too, and to explicitly call `aging-session.core/stop` on the session store when it needs to be stopped. 
The `stop` function will interrupt the sweeper thread so that it is otherwise not left dangling.



## License

Copyright © 2012-2018 Kira Inc.

This library originally developed by Alexander Hudek (https://github.com/akhudek)

Various updates and other changes in this fork by Gered King (https://github.com/gered)

Distributed under the Eclipse Public License, the same as Clojure.
