; Copyright (c) Nicolas Buduroi. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 which can be found in the file
; epl-v10.html at the root of this distribution. By using this software
; in any fashion, you are agreeing to be bound by the terms of this
; license.
; You must not remove this notice, or any other, from this software.

(ns lobos.migration
  "Migrations support."
  (:refer-clojure :exclude [complement defonce replace])
  (:require (clojure.contrib [sql :as sql])
            (lobos [analyzer :as analyzer]
                   [compiler :as compiler]
                   [connectivity :as conn]
                   [schema :as schema]))
  (:use (clojure [string :only [replace]]
                 [walk   :only [postwalk]])
        (clojure.java [io :only [file
                                 make-parents
                                 writer]])
        (clojure.contrib [def :only [name-with-attributes]])
        (clojure pprint)
        lobos.internal
        lobos.utils)
  (:import (java.sql Timestamp)
           (java.util Date)))

;; -----------------------------------------------------------------------------

;; ## Globals

(def *record* :stash)

(def *default-directory*
     (replace "lobos/migrations/"
              "/"
              java.io.File/separator))

(def *stash-file* "stash.clj")

(def *migrations-table* :schema_version)

(def *version* :version)
(def *description* :description)
(def *type* :type)
(def *script* :script)
(def *checksum* :checksum)
(def *installed_by* :installed_by)
(def *installed_on* :installed_on)
(def *execution_time* :execution_time)
(def *state* :state)
(def *current_version* :current_version)
(def *index_curent_version* :schema_version_index_current_version)
(def *schema_version_column_vec*
  [*version* *description* *type* *script* *checksum* *installed_by* *installed_on* *execution_time* *state* *current_version*])

;; -----------------------------------------------------------------------------

;; ## Action Complement

(defn reverse-rename [form]
  (postwalk
   #(if (and (seq? %) (= 'column (first %)))
      (let [[elem from _ to] %]
        `(~elem ~to :to ~from))
      %)
   form))

(defn complement [action]
  (cond 
    (= (first action) 'create)
    (apply list 'drop (rest action))
    (= (first action) 'alter)
    (let [[head & [[subaction & args]]]
          (split-with #(not (keyword? %)) action)]
      (case subaction
        :add (concat head [:drop] args)
        :rename (concat head [:rename] (reverse-rename args))
        nil))))

;; -----------------------------------------------------------------------------

;; ## Helpers

(defn- ljust [s n p]
  (apply str s (repeat (- n (count (str s))) p)))

(defn- current-timestamp []
  (Thread/sleep 15)
  (-> (Date.)
      .getTime
      (Timestamp.)
      str
      (replace #"\D" "")
      (ljust 17 \0)))

;; ### File Helpers

(defn- append [file content]
  (make-parents file)
  (with-open [wtr (writer file :append true)]
    (.write wtr "\n")
    (pprint content wtr)))

;; ### Stash File Helpers

(defn stash-file []
  (file *default-directory*
        *stash-file*))

(defn append-to-stash-file [action]
  (append (stash-file) action))

(defn clear-stash-file []
  (when (.exists (stash-file))
    (spit (stash-file) "")))

(defn read-stash-file []
  (when (.exists (stash-file))
    (read-string (str \[ (slurp (stash-file)) \]))))

;; ### Migration Files Helpers

(defn list-mfiles []
  (->> *default-directory*
       file
       .listFiles
       (filter #(not= % (stash-file)))
       (sort)))

(defn msg->mfile [& msg]
  (file (str *default-directory*
             (replace
              (apply join \_ (current-timestamp) msg)
              #"\s" "_")
             ".clj")))

(defn action->mfile [action]
  (let [[action & args] action
        [spec-or-schema args]  (optional symbol? args)
        [subaction args] (optional keyword? args)
        [element name] (first args)]
    (if (= action 'exec)
      (msg->mfile
       (or spec-or-schema "default")
       action)
      (msg->mfile
       (or spec-or-schema "default")
       action
       (when subaction (as-str subaction))
       element
       (as-str name)))))

(defn append-to-mfile [mfile do & [undo]]
  (append mfile `{:do ~do
                  ~@(when undo [:undo])
                  ~@(when undo [(vec undo)])}))

(defn mfile->version [mfile]
  (->> mfile
       .getName
       (re-find #"^[^_.]*")))

(defn version->migrations [version]
  (let [dir (replace *default-directory* "\\" "\\\\")
        re (re-pattern (str "^" dir version))]
    (->> (list-mfiles)
         (filter #(re-seq re (str %)))
         (map slurp)
         (map read-string)
         (map #(assoc % :version version)))))

;; ### Migrations Table Helpers

(defn create-migrations-table
  [db-spec sname]
  (when-not (-> (analyzer/analyze-schema db-spec sname)
                :elements
                *migrations-table*)
    (let [action (schema/table *migrations-table*
                               (schema/varchar *version*  255 :not-null :primary-key)
                               (schema/varchar *description* 100)
                               (schema/varchar *type* 10 :not-null)
                               (schema/varchar *script* 200 :not-null :unique)
                               (schema/integer *checksum*)
                               (schema/varchar *installed_by* 30 :not-null)
                               (schema/timestamp *installed_on* (schema/default (now)))
                               (schema/integer *execution_time*)
                               (schema/varchar *state* 15 :not-null)
                               (schema/boolean *current_version* :not-null)
                               (schema/index *index_curent_version* [*current_version*]))
          create-stmt (schema/build-create-statement action db-spec)]
      (execute create-stmt db-spec))))

(defn insert-versions
  [db-spec sname & versions]
  (let [installed-by (:user db-spec)
        installed-on (Timestamp. (.getTime (Date.)))
        values-vec [*version* nil "SQL" nil 0 installed-by installed-on 0 "SUCCESS" true]]
    (when-not (empty? versions)
      (sql/with-connection db-spec
        (apply sql/insert-values
               *migrations-table*
               *schema_version_column_vec*
               (map #(assoc values-vec 0 % 3 (str % ".sql")) versions))))))

(defn delete-versions
  [db-spec sname & versions]
  (when-not (empty? versions)
    (conn/with-connection db-spec
      (delete db-spec sname *migrations-table*
              (in :version (vec versions))))))

(defn query-migrations-table
  [db-spec sname]
  (conn/with-connection db-spec
    (map :version (query db-spec sname *migrations-table*))))

;; ### Commands Helpers

(defn pending-versions [db-spec sname]
  (->> (list-mfiles)
       (map mfile->version)
       (exclude (query-migrations-table db-spec
                                        sname))))

(defn do-migrations* [db-spec sname with versions]
  (let [migrations (->> versions
                        (map version->migrations)
                        flatten
                        (sort-by :version)
                        (when->> (= with :undo) reverse))]
    (binding [*record* nil]
      ;; TODO: transaction
      (doseq [migration migrations
              action (or (with migration) [:nop])]
        (println (as-str with "ing") (:version migration))
        (when (not= action :nop)
          (eval action))
        ((if (= with :do)
           insert-versions
           delete-versions)
         db-spec sname (:version migration))))))

(defn dump* [db-spec sname mfile actions]
  (append-to-mfile mfile actions (->> actions
                                      (map complement)
                                      (filter identity)
                                      seq))
  (insert-versions db-spec sname (mfile->version mfile)))

(defn record [db-spec sname action]
  (cond
   (= *record* :stash) (append-to-stash-file action)
   (= *record* :auto) (dump* db-spec sname
                             (action->mfile action)
                             [action])))
