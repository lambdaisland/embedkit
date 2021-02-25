(ns demo
  (:require [clojure.java.browse :refer [browse-url]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.embedkit.watch :as w :refer [watch! unwatch!]]))

(r/delete-all-dashboards! conn)

(def conn (e/connect {:user "admin@example.com"
                      :password "secret1"
                      ;; http://localhost:3000/admin/settings/embedding_in_other_applications
                      :secret-key "edd85e0d585cecaa9e77d203993566b13bda4c962281e3fa8e4877374a7addd7"}))

conn

(def db (e/find-database conn "datomic"))

(:id db)




(def card (e/native-card {:name "my card"
                          :database (:id db)
                          :sql "SELECT * FROM onze.journal_entry_line"}))

card

(r/browse! (e/find-or-create! conn card))




(keys (:by-hash @(:cache conn)))

(e/populate-cache conn)


(def bar-card (e/bar-chart card {:x-axis ["description"]
                                 :y-axis ["amount"]}))

(->> bar-card
     (e/find-or-create! conn)
     (r/browse!))




(r/browse! (e/find-or-create! conn (e/dashboard {:name "My dashboard"})))

(let [card (e/find-or-create! conn card)
      dashboard (e/find-or-create! conn (e/dashboard {:name "My dashboard"}))]
  (e/find-or-create! conn (e/dashboard-card {:card card
                                             :dashboard dashboard})))



(def dashboard (e/dashboard {:name "My dashboard"
                             :cards [{:card card
                                      :width 5 :height 5}
                                     {:card bar-card
                                      :x 5 :y 2}]}))

(r/browse! (e/find-or-create! conn dashboard))

(browse-url (e/embed-url conn (e/find-or-create! conn dashboard)))




(def biggest-accounts
  (-> (e/native-card {:name "Biggest accounts"
                      :database 2
                      :variables {:company_legal_code {} #_{:editable? true}}
                      :sql {:select ["account__name" "SUM(amount) AS total"]
                            :from ["onze.journal_entry_line"]
                            :where [:= "company.legal_code" "{{company_legal_code}}"]
                            :join ["onze.journal_entry_x_journal_entry_lines" [:= "journal_entry_line.db__id" "journal_entry_x_journal_entry_lines.journal_entry_lines"]
                                   "onze.ledger_x_journal_entries" [:= "journal_entry_x_journal_entry_lines.db__id" "ledger_x_journal_entries.journal_entries"]
                                   "onze.fiscal_year_x_ledgers" [:= "ledger_x_journal_entries.db__id" "fiscal_year_x_ledgers.ledgers"]
                                   "onze.company_x_fiscal_years" [:= "fiscal_year_x_ledgers.db__id" "company_x_fiscal_years.fiscal_years"]
                                   "tnt.company" [:= "company_x_fiscal_years.id" "company.uuid"]]
                            :group-by ["account__name"]
                            :order-by [["total" :desc]]}})
      (e/bar-chart {:x-axis ["account__name"]
                    :y-axis ["total"]})))



(def var-dashboard (e/dashboard {:name "Var dashboard"
                                 :cards [{:card biggest-accounts}]}))


(r/browse! (e/find-or-create! conn var-dashboard))

(def iframe-url (e/embed-url conn (e/find-or-create! conn var-dashboard) {:variables {:company_legal_code "BRE"}}))

(browse-url iframe-url)
