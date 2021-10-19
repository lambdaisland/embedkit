(ns init
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.embedkit.setup :as setup]))

(def config {:user "admin@example.com"
             :password "secret1"})

;; create admin user and enable embedded
(setup/init-metabase! config)
;; get the embedding secret key
(def config* (assoc config
                    :secret-key (setup/get-embedding-secret-key (e/connect config))))

(def conn (e/connect config*))


;; create the database


(def engine "presto")
(def details {:host "localhost"
              :port 4383
              :catalog "analytics"
              :user "."
              :password ""
              :ssl false
              :tunnel-enabled false})
(setup/create-db! conn "presto-db" engine details)

;; init finished here
;; demo

(def db (e/find-database conn "presto-db"))

(:id db)

(def card (e/native-card {:name "my card"
                          :database (:id db)
                          :sql "SELECT * FROM EXAMPLE_TENANT__BRANDATWORKS_13082021.journal_entry_line"}))

(r/browse! (e/find-or-create! conn card))
