(ns $package$.$name$
  (:use lobos.core
	flyway.config
     	lobos.schema
     	lobos.connectivity))

(defn migrate []
  (open-global db)
  (set-debug-level :sql)
  $cljmig$)
