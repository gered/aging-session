(defproject net.gered/aging-session "0.1.0-SNAPSHOT"
  :description         "Memory based ring session with expiry and time based mutation."
  :url                 "https://github.com/gered/aging-session"
  :license             {:name "Eclipse Public License"
                        :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies        [[ring/ring-core "1.9.4"]]

  :profiles            {:provided
                        {:dependencies [[org.clojure/clojure "1.10.0"]]}

                        :dev
                        {:dependencies   [[pjstadig/humane-test-output "0.11.0"]]
                         :injections     [(require 'pjstadig.humane-test-output)
                                          (pjstadig.humane-test-output/activate!)]}}

  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]

  :release-tasks       [["vcs" "assert-committed"]
                        ["change" "version" "leiningen.release/bump-version" "release"]
                        ["vcs" "commit"]
                        ["vcs" "tag" "v" "--no-sign"]
                        ["deploy"]
                        ["change" "version" "leiningen.release/bump-version"]
                        ["vcs" "commit" "bump to next snapshot version for future development"]
                        ["vcs" "push"]]
  )
