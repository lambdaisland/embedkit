(ns lookup-id 
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]))

(def conn (e/connect {:user "admin@example.com"
                      :password "secret1"
                      ;; http://localhost:3000/admin/settings/embedding_in_other_applications
                      :secret-key "6fa6b6600d27ff276d3d0e961b661fb3b082f8b60781e07d11b8325a6e1025c5"}))
conn

(def db (e/find-database conn "example_tenant"))


;;
(e/fetch-database-fields conn (:id db))
(e/table-id conn "example_tenant" "enzo" "Denormalised General Ledger entries - Draft and Posted")
(e/field-id conn "example_tenant" "enzo" "Denormalised General Ledger entries - Draft and Posted" "document_date")
