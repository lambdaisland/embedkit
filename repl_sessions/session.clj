(ns repl-sessions.session
  (:require [clojure.java.browse :refer [browse-url]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r :refer [browse!]]
            [lambdaisland.embedkit.watch :as w :refer [watch! unwatch!]]))

;; Start embedkit connection, this will grab a token from Metabase and hold it
;; for later requests. It also instantiates a Hato (Java 11 HttpClient wrapper)
;; instance, and keeps a cache for certain requests.
(def conn (e/connect {:user "admin@example.com"
                      :password "secret1"
                      :secret-key "edd85e0d585cecaa9e77d203993566b13bda4c962281e3fa8e4877374a7addd7"
                      #_#_:middleware (conj hato.middleware/default-middleware
                                            r/print-request-mw)}))

(e/populate-cache conn)

;; We'll need to know the numeric id of our database in Metabase. This is an
;; example of something that gets cached.
(def my-db (e/find-database conn "datomic"))
(:id my-db)
;; => 2

;; Simple example to warm up
(->> (e/native-card {:name "my card"
                     :database (:id my-db)
                     :sql "SELECT * FROM onze.company"})
     (e/find-or-create! conn)
     browse!)

;; Now let's decorate this, the bar-chart function changes the visualiztion from
;; a table to a bar chart. You can configure certain options, like which
;; dimensions to show on x and y axis.
;;
;; I'm using HoneySQL syntax here, which I quite like so you don't have to write
;; SQL by mashing together strings, but you can create the SQL however you like.

(defn biggest-accounts []
  (-> (e/native-card {:name "account bars var"
                       :database 2
                       :sql {:select ["account__name" "SUM(amount) AS total"]
                             :from ["onze.journal_entry_line"]
                             :group-by ["account__name"]
                             :order-by [["total" :desc]]}})
      (e/bar-chart {:x-axis ["account__name"]
                    :y-axis ["total"]})))

(->> (biggest-accounts)
     (e/create! conn)
     (browse!))

(defn accounts-payable []
  (-> (e/native-card {:name "Accounts payable - Top 10"
                       :database (:id my-db)
                       :sql {:select ["onze.account.number"
                                      "onze.account.name"
                                      "SUM(IF(flow = (SELECT db__id FROM onze.db__idents WHERE ident = ':flow/credit'), amount, (amount * -1))) AS balance"]
                             :from ["onze.journal_entry_line"]
                             :join ["onze.account"
                                    [:= "onze.account.db__id" "onze.journal_entry_line.account"]]
                             :where [:= "onze.account.subtype"
                                     {:select ["db__id"]
                                      :from ["onze.db__idents"]
                                      :where [:= "ident" "':account.subtype/accounts-payable'"]}]
                             :group-by ["account.number" "account.name"]
                             :order-by [["balance" :desc]]
                             :limit 10}})
      (e/bar-chart {:x-axis ["name"] :y-axis ["balance"]})))

;; More complex query, show top accounts payable
(->> (accounts-payable)
     (e/create! conn)
     (browse!))

(watch! [:card 48])
(watch! [:dashboard 1])

(let [card1 (e/create! conn (accounts-payable))
      card2 (e/create! conn (biggest-accounts))
      dash (e/create! conn (e/dashboard {:name "Foo"}))]
  (e/create! conn (e/dashboard-card {:card card1 :dashboard dash
                                     :x 0 :y 0 :width 5 :height 5}))
  (e/create! conn (e/dashboard-card {:card card2 :dashboard dash
                                     :x 5 :y 0 :width 5 :height 5}))
  (e/enable-embedding! conn dash)
  (browse-url (str (e/embed-url conn dash))))

(->> {:name "Foo"
      :cards [{:card (accounts-payable)
               :width 7 :height 5}
              {:card (biggest-accounts-company)
               :x 7
               :width 7 :height 5}]}
     e/dashboard
     (e/find-or-create! conn)
     (e/embed-url conn)
     str
     browse-url
     time)

(->> (-> (e/native-card {:name "my card"
                          :database (:id my-db)
                          :sql "SELECT * FROM onze.company"})

         )
     (e/find-or-create! conn)
     browse!)

@(:cache conn)

(e/mb-get conn [:card 110])

(doseq [card (:body (e/mb-get conn [:card 111]))]
  (when-let [hash (get-in card [:visualization_settings :embedkit.hash])]
    ))

(r/delete-all-cards! conn)
(r/delete-all-dashboards! conn)
(reset! (:cache conn) {})

(defn biggest-accounts-company []
  (-> (e/native-card {:name "account bars var"
                       :database 2
                       :variables {:company_legal_code {}}
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

(->> {:name "My dashboard"
      :cards [#_{:card (accounts-payable)
                 :width 7 :height 5}
              {:card (biggest-accounts-company)
               :x 7
               :width 7 :height 5}]}
     e/dashboard
     (e/find-or-create! conn)
     (#(e/embed-url conn % {:variables {:company_legal_code "BRE"}}))
     str
     browse-url
     #_time)

(watch! conn [:dashboard ])
(browse! ddd)
(def after (:body (e/mb-get conn [:dashboard 62])))

(dissoc before :ordered_cards)
(dissoc after :ordered_cards)

(w/only-changes before after)

(map :parameter_mappings (:ordered_cards before))
(map :parameter_mappings (:ordered_cards after))

(browse-url (e/embed-url conn
                         (:body (e/mb-get conn [:dashboard 56]))
                         {:variables {:id "BRE"}}))

(reset! (:cache conn) {})

(require '[lambdaisland.embedkit :as e]
         '[lambdaisland.embedkit.repl :as r])

(def conn (e/connect {:user "admin@example.com" :password "..." :secret-key "..."}))

(def db (e/find-database "orders"))

(e/find-or-create!
 conn
 (e/dashboard {:name "My sales dashboard"
               :cards [{:card (my-card-fn)
                        :x 5 :y 0
                        :width 12 :height 10}]}))

;; Open the dashboard in the browser, REPL helper for local testing
(r/browse! dashboard)

;; Get an embed-url that you can use in an iframe
(e/embed-url conn dashboard)

(e/native-card {:name "Monthly revenue"
                :database {:id 2}
                :sql {:select ["month" "SUM(amount) AS total"]
                      :from ["orders"]
                      :group-by ["month"]
                      :order-by ["month"]}})
