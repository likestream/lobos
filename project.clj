(defproject lobos "0.8.0-SNAPSHOT"
  :description
  "A library to create and manipulate SQL database schemas."
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [postgresql "9.0-801.jdbc4"]]
  :dev-dependencies [[swank-clojure "1.4.0-SNAPSHOT"]
                     [clojure-source "1.2.0"]
                     [lein-clojars "0.6.0"]
                     [marginalia "0.5.0"]
                     [cljss "0.1.1"]
                     [hiccup "0.3.1"]
                     [clj-help "0.2.0"]
                     [com.h2database/h2 "1.3.154"]]
  :jvm-opts ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n"]
  :jar-exclusions [#"www.clj"])
