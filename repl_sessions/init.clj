(ns init
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.automation :as automation]))

(def config {:user "admin@example.com"
             :password "secret1"})

;; create admin user and enable embedded
(automation/init-metabase! config)
;; get the embedding secret key
(def config* (assoc config
                    :secret-key (automation/get-embedding-secret-key (e/connect config))))

(def conn (e/connect config*))

;; create the database
(automation/create-presto-db! conn "datomic")

;; init finished here
;; demo

(def db (e/find-database conn "datomic"))

(:id db)

(def card (e/native-card {:name "my card"
                          :database (:id db)
                          :sql "SELECT * FROM EXAMPLE_TENANT__BRANDATWORKS_13082021.journal_entry_line"}))

(r/browse! (e/find-or-create! conn card))
