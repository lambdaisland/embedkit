(ns lookup-id
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]))

(def conn
  (e/connect (read-string (slurp "dev/config.edn"))))

conn

(def db (e/find-database conn "example_tenant"))
;;
(e/fetch-database-fields conn (:id db))
(e/table-id conn "example_tenant" "enzo" "Denormalised General Ledger entries - Draft and Posted")
(e/field-id conn "example_tenant" "enzo" "Denormalised General Ledger entries - Draft and Posted" "document_date")

(get-in
 @(:cache conn)
 [:databases "example_tenant" :schemas "enzo" :tables  "Denormalised General Ledger entries - Draft and Posted"])

(e/fetch-users conn :all)
(e/user-id conn "admin@example.com")

(e/fetch-all-groups conn)
(e/group-id conn "Administrators")

(e/trigger-db-fn! conn "example_tenant" :sync_schema)
(e/trigger-db-fn! conn "example_tenant" :rescan_values)
