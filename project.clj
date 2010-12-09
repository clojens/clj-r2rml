(defproject clj-r2rml "0.0.1-SNAPSHOT"
  :description "A implementation of the R2RML proposal by the W3C to map relational data to RDF linked data"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [clojureql "1.0.0-beta2-SNAPSHOT"]]
  :dev-dependencies [[leiningen/lein-swank "1.2.0-SNAPSHOT"]
                     [cdt "1.2"]])
;  :jvm-opts ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8030"])
