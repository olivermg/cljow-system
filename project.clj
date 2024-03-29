(defproject cljow-system "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[cljow-log "0.1.2-SNAPSHOT"]
                 [org.clojure/clojure "1.10.0" :scope "provided"]
                 [org.clojure/core.async "0.4.490"]
                 [ubergraph "0.5.2"]]
  :repl-options {:init-ns cljow-system.core}
  :pedantic? :abort

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy" "clojars"]
                  #_["clean"]
                  #_["uberjar"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  #_["vcs" "push"]])
