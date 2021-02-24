(ns repl-sessions.session
  (:require [clojure.java.browse :refer [browse-url]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]))

;; Start embedkit connection, this will grab a token from Metabase and hold it
;; for later requests. It also instantiates a Hato (Java 11 HttpClient wrapper)
;; instance, and keeps a cache for certain requests.
(def my-conn (e/connect {:user "admin@example.com"
                         :password "secret1"}))

#_ (reset! (:cache my-conn) {})

;; We'll need to know the numeric id of our database in Metabase. This is an
;; example of something that gets cached.
(def my-db (e/find-database my-conn "datomic"))

(:id my-db)
;; => 2

;; Clean up after experimenting. "Card" here means metabase query, they use the
;; terms interchangably
(defn delete-all-cards! []
  (doseq [{:keys [id]} (:body (e/mb-get my-conn [:card]))]
    (println "Deleting card" id)
    (e/mb-delete my-conn [:card id])))

;; Helper to open the result of an operation
(defn browse! [res]
  (browse-url (str "http://localhost:3000/"
                   (cond
                     (str/includes? (-> res meta ::e/request :url)
                                    "/api/card")
                     "question"
                     (str/includes? (-> res meta ::e/request :url)
                                    "/api/dashboard")
                     "dashboard")
                   "/"
                   (:id res))))

(defonce watches (atom {}))

;; "Watch" a given resource, this keep re-fetching it from the API, and printing
;; any keys that have changed. This allows us to make changes in the UI and see
;; how they correspond with the data.
(defn watch! [path]
  (let [card (:body (e/mb-get my-conn path))]
    (swap! watches assoc path card)
    (future
      (loop []
        (let [before (get @watches path)]
          (when before
            (let [card (:body (e/mb-get my-conn path))]
              (when (not= before card)
                (println "--------------------------------------------")
                (clojure.pprint/pprint (into {}
                                             (remove (fn [[k v]]
                                                       (= v (get before k))))
                                             card))
                (swap! watches path card)
                (Thread/sleep 1000))
              (recur))))))))

(defn unwatch! [id]
  (swap! watches dissoc id))

;; Simple example to warm up
(->> (e/native-query {:name "my card"
                      :database (:id my-db)
                      :sql "SELECT * FROM onze.company"})
     (e/create! my-conn)
     browse!)

;; Now let's decorate this, the bar-chart function changes the visualiztion from
;; a table to a bar chart. You can configure certain options, like which
;; dimensions to show on x and y axis.
;;
;; I'm using HoneySQL syntax here, which I quite like so you don't have to write
;; SQL by mashing together strings, but you can create the SQL however you like.

(defn biggest-accounts []
  (-> (e/native-query {:name "account bars var"
                       :database 2
                       :sql {:select ["account__name" "SUM(amount) AS total"]
                             :from ["onze.journal_entry_line"]
                             :group-by ["account__name"]
                             :order-by [["total" :desc]]}})
      (e/bar-chart {:x-axis ["account__name"]
                    :y-axis ["total"]})))

(->> (biggest-accounts)
     (e/create! my-conn)
     (browse!))

(defn accounts-payable []
  (-> (e/native-query {:name "Accounts payable - Top 10"
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
     (e/create! my-conn)
     (browse!))

(watch! [:card 48])
(watch! [:dashboard 1])

(let [card1 (e/create! my-conn (accounts-payable))
      card2 (e/create! my-conn (biggest-accounts))
      dash (e/create! my-conn (e/dashboard {:name "Foo" :description "ok"}))]
  (e/create! my-conn (e/dashboard-card {:card card1 :dashboard dash
                                        :x 0 :y 0 :width 5 :height 5}))
  (e/create! my-conn (e/dashboard-card {:card card2 :dashboard dash
                                        :x 5 :y 0 :width 5 :height 5}))
  (browse! dash))

(watch! [:dashboard 6])
