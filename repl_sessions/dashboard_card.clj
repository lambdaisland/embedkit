(ns dashboard-card
  (:require
   [clojure.java.browse :refer [browse-url]]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [lambdaisland.embedkit :as e]
   [lambdaisland.embedkit.repl :as r]
   [lambdaisland.embedkit.watch :as w :refer [watch! unwatch!]]))

;; create connection
(def conn (e/connect (read-string (slurp "dev/config.edn"))))
conn

;; create database
(def db (e/find-database conn "example_tenant"))
(:id db)

;; create card
(def card (e/native-card {:name "my card"
                          :database (:id db)
                          :sql "SELECT * FROM enzo.account"}))
card
(r/browse! (e/find-or-create! conn card))

;; create dashboard with dashboard-card
(let [card1 (e/native-card {:name "my card 1"
                            :database (:id db)
                            :sql "SELECT * FROM enzo.account"})
      card2 (e/native-card {:name "my card 2"
                            :database (:id db)
                            :sql "SELECT * FROM enzo.currency"})
      dash (e/find-or-create! conn (e/dashboard {:name "Foo"}))]
  (e/find-or-create! conn (e/dashboard-card {:card card1 :dashboard dash
                                             :x 0 :y 0 :width 5 :height 5}))
  (e/find-or-create! conn (e/dashboard-card {:card card2 :dashboard dash
                                             :x 5 :y 0 :width 5 :height 5}))
  (browse-url (str (e/embed-url conn dash))))
