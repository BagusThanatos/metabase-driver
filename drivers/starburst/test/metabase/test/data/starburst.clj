;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at

;;     http://www.apache.org/licenses/LICENSE-2.0

;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns metabase.test.data.starburst
  "Starburst driver test extensions."
  (:require [clojure.string :as str]
            [metabase.config :as config]
            [metabase.connection-pool :as connection-pool]
            [metabase.driver :as driver]
            [metabase.driver.ddl.interface :as ddl.i]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql.util :as sql.u]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.sql-jdbc :as sql-jdbc.tx]
            [metabase.test.data.sql-jdbc.execute :as execute]
            [metabase.test.data.sql-jdbc.load-data :as load-data]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.util :as u]
            [metabase.util.log :as log])
  (:import [java.sql Connection DriverManager PreparedStatement]))

;; JDBC SQL
(sql-jdbc.tx/add-test-extensions! :starburst)

(def ^:private test-catalog-name "test_data")

(defmethod tx/sorts-nil-first? :starburst [_ _] false)

;; during unit tests don't treat Trino as having FK support
(defmethod driver/database-supports? [:starburst :set-timezone] [_ _ _] true)
(defmethod driver/database-supports? [:starburst :test/time-type] [_ _ _] false)
(defmethod driver/database-supports? [:starburst :test/timestamptz-type] [_ _ _] false)
(defmethod driver/database-supports? [:starburst :test/dynamic-dataset-loading] [_ _ _] (not config/is-test?))
(defmethod driver/database-supports? [:starburst :metabase.query-processor-test.string-extracts-test/concat-non-string-args] [_ _ _] false)
(defmethod driver/database-supports? [:starburst :metabase.query-processor-test.alternative-date-test/yyyymmddhhss-binary-timestamps] [_ _ _] false)

(doseq [[base-type db-type] {:type/BigInteger             "BIGINT"
                             :type/Boolean                "BOOLEAN"
                             :type/Date                   "DATE"
                             :type/DateTime               "TIMESTAMP"
                             :type/DateTimeWithTZ         "TIMESTAMP WITH TIME ZONE"
                             :type/DateTimeWithZoneID     "TIMESTAMP WITH TIME ZONE"
                             :type/DateTimeWithZoneOffset "TIMESTAMP WITH TIME ZONE"
                             :type/Decimal                "DECIMAL"
                             :type/Float                  "DOUBLE"
                             :type/Integer                "INTEGER"
                             :type/Text                   "VARCHAR"
                             :type/Time                   "TIME"
                             :type/TimeWithTZ             "TIME WITH TIME ZONE"}]
  (defmethod sql.tx/field-base-type->sql-type [:starburst base-type] [_ _] db-type))

(defmethod tx/dbdef->connection-details :starburst
  [_ _ {:keys [_database-name]}]
  {:host                               (tx/db-test-env-var-or-throw :starburst :host "localhost")
   :port                               (tx/db-test-env-var :starburst :port "8082")
   :user                               (tx/db-test-env-var-or-throw :starburst :user "metabase")
   :additional-options                 (tx/db-test-env-var :starburst :additional-options nil)
   :prepared-optimized                 (tx/db-test-env-var :starburst :prepared-optimized (not= (System/getProperty "explicitPrepare" "true") "true"))
   :ssl                                (tx/db-test-env-var :starburst :ssl "false")
   :kerberos                           (tx/db-test-env-var :starburst :kerberos "false")
   :kerberos-principal                 (tx/db-test-env-var :starburst :kerberos-principal nil)
   :kerberos-remote-service-name       (tx/db-test-env-var :starburst :kerberos-remote-service-name nil)
   :kerberos-use-canonical-hostname    (tx/db-test-env-var :starburst :kerberos-use-canonical-hostname nil)
   :kerberos-credential-cache-path     (tx/db-test-env-var :starburst :kerberos-credential-cache-path nil)
   :kerberos-keytab-path               (tx/db-test-env-var :starburst :kerberos-keytab-path nil)
   :kerberos-config-path               (tx/db-test-env-var :starburst :kerberos-config-path nil)
   :kerberos-service-principal-pattern (tx/db-test-env-var :starburst :kerberos-service-principal-pattern nil)
   :catalog                            test-catalog-name
   :schema                             (tx/db-test-env-var :starburst :schema nil)})

(defmethod execute/execute-sql! :starburst
  [& args]
  (apply execute/sequentially-execute-sql! args))

(defmethod load-data/chunk-size :starburst
  [_driver _dbdef _tabledef]
  nil)

(defmethod load-data/row-xform :starburst
  [_driver _dbdef _tabledef]
  (load-data/add-ids-xform))

(defmethod ddl/insert-rows-dml-statements :starburst
  [driver table-identifier rows]
  (def %rows rows)
  (binding [driver/*compile-with-inline-parameters* true]
    ((get-method ddl/insert-rows-dml-statements :sql-jdbc/test-extensions) driver table-identifier rows)))

;;; it seems to be significantly faster to load rows in batches of 500 in parallel than to try to load all the rows in
;;; a few giant SQL statement. It seems like batch size = 5000 is a working limit here but
(defmethod load-data/do-insert! :starburst
  [driver ^Connection conn table-identifier rows]
  (dorun
   (pmap
    (fn [rows]
      (let [statements (ddl/insert-rows-dml-statements driver table-identifier rows)]
        (doseq [[^String sql & params] statements]
          (assert (empty? params))
          (try
            (with-open [stmt (.createStatement conn)]
              (let [[_tag _identifier-type components] table-identifier
                    table-name                         (last components)]
                (.execute stmt sql)
                (log/infof "[%s] Inserted %d rows into %s." driver (count rows) table-name)))
            (catch Throwable e
              (throw (ex-info (format "[%s] Error executing SQL: %s" driver (ex-message e))
                              {:driver driver, :sql sql}
                              e)))))))
    (partition-all 500 rows))))

(defmethod sql.tx/drop-db-if-exists-sql :starburst [_ _] nil)
(defmethod sql.tx/create-db-sql         :starburst [_ _] nil)

(def ^:private ^String test-schema "default")

(defmethod sql.tx/qualified-name-components :starburst
  ;; use the default schema from the in-memory connector
  ([_ _db-name]                      [test-catalog-name test-schema])
  ([_ db-name table-name]            [test-catalog-name test-schema (tx/db-qualified-table-name db-name table-name)])
  ([_ db-name table-name field-name] [test-catalog-name test-schema (tx/db-qualified-table-name db-name table-name) field-name]))

(defmethod sql.tx/pk-sql-type :starburst
  [_]
  "INTEGER")

(defmethod sql.tx/create-table-sql :starburst
  [driver dbdef tabledef]
  ;; Presto doesn't support NOT NULL columns
  (let [tabledef (update tabledef :field-definitions (fn [field-defs]
                                                       (for [field-def field-defs]
                                                         (dissoc field-def :not-null?))))
        ;; strip out the PRIMARY KEY stuff from the CREATE TABLE statement
        sql      ((get-method sql.tx/create-table-sql :sql/test-extensions) driver dbdef tabledef)]
    (str/replace sql #", PRIMARY KEY \([^)]+\)" "")))

(defmethod ddl.i/format-name :starburst
  [_driver table-or-field-name]
  (str/replace table-or-field-name #"-" "_"))

;; Presto doesn't support FKs, at least not adding them via DDL
(defmethod sql.tx/add-fk-sql :starburst
  [_driver _dbdef _tabledef _fielddef]
  nil)

(defn- describe-schema-sql
  "The SHOW TABLES statement that will list all tables for the given `catalog` and `schema`."
  {:added "0.39.0"}
  [driver catalog schema]
  (str "SHOW TABLES FROM " (sql.u/quote-name driver :schema catalog schema)))

(defmethod tx/dataset-already-loaded? :starburst
  [driver dbdef]
  ;; check and make sure the first table in the dbdef has been created.
  (let [tabledef   (first (:table-definitions dbdef))
        ;; table-name should be something like test_data_venues
        table-name (tx/db-qualified-table-name (:database-name dbdef) (:table-name tabledef))
        _          (assert (some? tabledef))
        details    (tx/dbdef->connection-details driver :db dbdef)
        jdbc-spec  (sql-jdbc.conn/connection-details->spec driver details)]
    (try
      (sql-jdbc.execute/do-with-connection-with-options
       driver
       jdbc-spec
       {:write? false}
       (fn [^Connection conn]
         ;; look at all the tables in the test schema.
         (let [^String sql (#'describe-schema-sql driver test-catalog-name test-schema)]
           (with-open [stmt (.createStatement conn)
                       rset (.executeQuery stmt sql)]
             (loop []
               ;; if we see the table with the name we're looking for, we're done here; otherwise keep iterating thru
               ;; the existing tables.
               (cond
                 (not (.next rset))                       false
                 (= (.getString rset "table") table-name) true
                 :else                                    (recur)))))))
      (catch Throwable _e
        false))))
