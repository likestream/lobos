(ns lobos.flyway
  "Support for generating Java migrations (as supported by the Flyway
   migration framework). These Java migrations in turn use lobos to
   describe the migrations in Clojure."
  (:refer-clojure :exclude [replace])
  (:use [clojure.string :only [replace trim blank?]]
        [clojure.set :only [rename-keys]]
        [clojure.java [io :only [file writer]]]
        [clojure.contrib.strint :only [<<]]
        [clojure.contrib.cond :only [cond-let]]
        [clojure.contrib.logging :only [enabled? spy]]
        [clojure.contrib.duck-streams :only [reader]]
        [clojure.contrib.java-utils :only [read-properties]]        
        [lobos.utils :only [make-parents]])
  (:import [java.io File]
           [org.antlr.stringtemplate StringTemplate StringTemplateGroup]))

;; ## Globals

(def *url* "url")
(def *clj* "clj")
(def *java* "java")
(def *name* "name")
(def *user* "user")
(def *config* "config")
(def *package* "package")
(def *flyway* "flyway")
(def *password* "password")
(def *classname* "classname")
(def *package-dir* "packagedir")
(def *clj-migration-code* "cljmig")
(def *file-name-format* "$name$.$extn$")
(def *default-migration-dir* "lib/dev/db/migration/")

(def *name-placeholder* "$name$")
(def *extn-placeholder* "$extn$")

(def *templates-dir* "lobos/templates/")
(def *java-template* "java-migration-template")
(def *clj-template* "clojure-migration-template")
(def *clj-config-template* "clojure-config-template")

(def *project-file* "project.clj")
(def *flyway-properties-file* "conf/flyway.properties")

;; ## Helpers

(defn- if-enabled-then-output [log-level message]
  (when (enabled? log-level)
    (spy message)))

(defn- set-attributes [template amap]
  (doseq [[param-name param-val] (seq amap)]
    (.setAttribute template param-name param-val))
  (.toString template))

(defn- generate-code-using-template [f template amap]
  (let [contents (.toString f)]
    (spit f (set-attributes template amap))
    (println (<< "Created ~{contents}."))))

;; ## File Helpers

(defn- read-from-properties-file []
  (let [props (into{} (read-properties *flyway-properties-file*))
        old-keys (keys props)
        new-keys (map (fn [x] (keyword (replace x "flyway." ""))) old-keys)]
    (if-enabled-then-output :debug (<< "The data read from conf/properties file is: ~{props}"))
    (rename-keys props (zipmap old-keys new-keys))))

(defn- read-from-project-file []
  (let [colon-flyway (str (keyword *flyway*))
        contents (line-seq (reader *project-file*))
        db-credentials (filter #(.startsWith (trim %) colon-flyway) contents)]
    (if-not (empty? db-credentials)
      (let [db-credential-line (first db-credentials)
            map-string (trim (subs db-credential-line (+ (.indexOf db-credential-line colon-flyway) (count colon-flyway))))
            pure-map-string (replace map-string ")" "")]
        (if-enabled-then-output :debug (<< "The data read from project.clj file is: ~{pure-map-string}"))
        (rename-keys (read-string pure-map-string) {:username :user})))))

(defn- get-db-credentials-from-file []
  (let [exists-project-file (.exists (File. *project-file*))
        exists-properties-file (.exists (File. *flyway-properties-file*))]
    (cond-let [db-credentials]
              (true? exists-properties-file) (read-from-properties-file)
              (true? exists-project-file) (read-from-project-file)
              :else {})))

(defn- get-db-credentials []
  (let [db-credentials (get-db-credentials-from-file)]
    (if-enabled-then-output :debug (<< "The DB credentials as specified in one of the configuration files are: ~{db-credentials}"))
    (if (empty? db-credentials)
      (throw (Exception. (<< "No database credentials specified either in ~{*project-file*} or in ~{*flyway-properties-file*}."))))
    db-credentials))

(defn- get-file-name [package-name migration-name extn]
  (let [migration-dir (get-dir-name package-name)
        file-name (replace (replace *file-name-format* *name-placeholder* migration-name) *extn-placeholder* extn)]
    (if (= file-name ".")
      (<< "~{*default-migration-dir*}~{migration-dir}/")
      (<< "~{*default-migration-dir*}~{migration-dir}/~{file-name}"))))

(defn get-file [package-name migration-name extn]
  (file (get-file-name package-name migration-name extn)))

(defn- get-dir-name [package-name]
  (replace package-name "." "/"))

;; ## Code Generation

(defn generate-java-migration [package-name migration-name & lobos-statements]
  {:pre [(not (blank? package-name))
         (not (blank? migration-name))
         (not (empty? lobos-statements))
         (not (empty? (first lobos-statements)))]}
  (let [clj-file (get-file package-name migration-name *clj*)
        clj-config-file (get-file *flyway* *config* *clj*)
        java-file (get-file package-name migration-name *java*)
        template-group (StringTemplateGroup. "tmpGroup")
        db-credentials (get-db-credentials)
        {classname :driver url :url user :user password :password} db-credentials]
    (when (or (.exists java-file) (.exists clj-file))
      (throw (IllegalArgumentException. "Migration name is already taken.")))
    ;; (.setRefreshInterval template-group 0)
    (make-parents clj-file)
    (make-parents clj-config-file)
    (if-enabled-then-output :debug "created parent directories")
    (generate-code-using-template clj-file
                                  (.getInstanceOf template-group (str *templates-dir* *clj-template*))
                                  {*package* package-name *name* migration-name *clj-migration-code* (str (first lobos-statements))})
    (generate-code-using-template clj-config-file
                                  (.getInstanceOf template-group (str *templates-dir* *clj-config-template*))
                                  {*classname* classname *url* url *user* user *password* password})
    (generate-code-using-template java-file
                                  (.getInstanceOf template-group (str *templates-dir* *java-template*))
                                  {*package* package-name *name* migration-name *package-dir* (get-dir-name package-name)})))
