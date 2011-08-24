(ns lobos.test.flyway
  (:refer-clojure :exclude [compile replace])
  (:use [clojure.test]
        [clojure.string :only [replace]]
        [clojure.java.io :only [delete-file]]
        [clojure.contrib.java-utils :only [delete-file-recursively]]
        [lobos.flyway])
  (:import [java.io File]))

;; ## Globals

(def *top-level-dir* "a")
(def *package-name* "a.b")
(def *migration-name* "V1.2")

;; ## Fixtures

(defn file-cleanup-fixture [f]
  (let [clj-mig-file-name (get-file *package-name* *migration-name* *clj*)
        java-mig-file-name (get-file *package-name* *migration-name* *java*)
        top-level-package-dir (get-file *top-level-dir* "" "")
        top-level-config-dir (get-file *flyway* "" "")]
    (f)
    (if (.exists clj-mig-file-name)
      (delete-file clj-mig-file-name))
    (if (.exists java-mig-file-name)
      (delete-file java-mig-file-name))
    (if (.exists top-level-package-dir)
      (delete-file-recursively top-level-package-dir))
    (if (.exists top-level-config-dir)
      (delete-file-recursively top-level-config-dir))))

(use-fixtures :once file-cleanup-fixture)

;; ## Tests

(deftest should-return-file-when-name-extn-are-specified
  (is (not (.isDirectory (get-file *package-name* *migration-name* "java")))))

(deftest should-return-directory-when-name-extn-are-not-specified
  (is (.isDirectory (get-file "" "" ""))))

(deftest generate-java-migration-without-package-name
  (is (thrown? java.lang.AssertionError (generate-java-migration "" *migration-name* '()))))

(deftest generate-java-migration-without-migration-name
  (is (thrown? java.lang.AssertionError (generate-java-migration *package-name* "" '()))))

(deftest generate-java-migration-without-lobos-statements
  (is (thrown? java.lang.AssertionError (generate-java-migration *package-name* *migration-name* '()))))

(deftest generate-java-migration-with-nil-lobos-statements
  (is (thrown? java.lang.AssertionError (generate-java-migration *package-name* *migration-name* nil))))

(deftest generate-working-java-migration
  (is (= nil (generate-java-migration *package-name* *migration-name* '(create db (schema :likestream))))))
