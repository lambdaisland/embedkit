(ns create-db-conn
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.setup :as setup]))

(def conn
  (e/connect (read-string (slurp "dev/config.edn"))))

conn
;; create the database
(comment
;; Example for Presto 
;; Presto driver is going to be deprecated in new version of Metabase.
  (def db-conn-name "presto-db")
  (def engine "presto")
  (def details {:host "localhost"
                :port 4383
                :catalog "analytics"
                :user "."
                :password ""
                :ssl false
                :tunnel-enabled false}))
(comment
;; Example for Postgres 
  (def db-conn-name "kkk")
  (def engine "postgres")
  (def details {:host "localhost"
                :port 5432
                :dbname "example_tenant"
                :user (System/getenv "POSTGRESQL__USER")
                :password (System/getenv "POSTGRESQL__PASSWORD")
                :ssl false
                :tunnel-enabled false}))

(setup/create-db! conn db-conn-name engine details)
