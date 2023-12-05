(ns dashboard-card
  (:require
   [clojure.java.browse :refer [browse-url]]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [lambdaisland.embedkit :as e]
   [lambdaisland.embedkit.repl :as r]
   [lambdaisland.embedkit.watch :as w :refer [watch! unwatch!]]))

;; create atom connection
(def conn (atom nil))
(reset! conn (e/connect (read-string (slurp "dev/config.edn"))))

(prn "Is conn an Atom" (instance? clojure.lang.Atom conn))

;; find database by name
(def db (e/find-database conn "Sample Database"))
(:id db)

;; populate cache
(e/populate-cache conn)

;; create card
(def card (e/native-card {:name "order card"
                          :variables {:start_date {:editable? true
                                                   :type "date"}
                                      :end_date {:editable? true
                                                 :type "date"}}
                          :database (:id db)
                          :sql "SELECT * FROM orders [[WHERE created_at BETWEEN {{start_date}} AND {{end_date}}]]"}))
card
(r/browse! (e/find-or-create! conn card))

;; create dashboard with dashboard-card
(let [card1 (e/native-card {:name "order card"
                            :database (:id db)
                            :sql "SELECT * FROM orders"})
      card2 (e/native-card {:name "invoice card"
                            :database (:id db)
                            :sql "SELECT * FROM invoices"})
      dash (e/find-or-create! conn (e/dashboard {:name "Sample DB dashboard"
                                                 :cards [{:card card1
                                                          :x 0 :y 0
                                                          :width 5 :height 5}
                                                         {:card card2
                                                          :x 5 :y 0
                                                          :width 5 :height 5}]}))]
  (browse-url (str (e/embed-url conn dash))))

(comment
 (let [card1 (e/native-card {:name "order card"
                            :database (:id db)
                            :sql "SELECT id FROM orders"})
      card2 (e/native-card {:name "invoice card"
                            :database (:id db)
                            :sql "SELECT id FROM invoices"})
      ]
   (try
     (e/find-or-create! conn (e/dashboard {:name "Sample DB 3 dashboard"
                                                 :cards [{:card card1
                                                          :x 0 :y 0
                                                          :width 5 :height 5}
                                                         {:card card2
                                                          :x 5 :y 0
                                                          :width 5 :height 5}]}))
     (catch Exception e
       (prn e)
       (prn (ex-data e)))
       )
     )
  (browse-url (str (e/embed-url conn dash)))))

