(defproject net.gered/aging-session "0.1.0-SNAPSHOT"
  :description "Memory based ring session with expiry and time based mutation."
  :url "https://github.com/gered/aging-session"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[ring/ring-core "1.2.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]]}})