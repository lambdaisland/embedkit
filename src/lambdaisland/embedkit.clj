(ns lambdaisland.embedkit
  (:require [hato.client :as http]
            [hato.middleware :as hato-mw]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hasch.core :as hasch]
            [hasch.hex :as hasch-hex]
            [clojure.walk :as walk]
            [honeysql.core :as honey])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn rand-id []
  (str (UUID/randomUUID)))

(defn hex->base36 [^String hex]
  (.toString (BigInteger. hex 16) 36))

(defn edn->hash [edn]
  (let [edn (walk/postwalk (fn [x]
                             (if (and x (str/starts-with? (.getName ^Class (class x)) "java.time"))
                               (str x)
                               x))
                           edn)]
    (hex->base36 (hasch-hex/encode (take 32 (hasch/edn-hash edn))))))

(defn as-str [v]
  (if (instance? clojure.lang.Named v)
    (name v)
    (str v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Plumbing

;; Use clojure.data.json instead of Cheshire
(defmethod hato-mw/coerce-form-params :application/json
  [{:keys [form-params json-opts]}]
  (apply json/write-str form-params (mapcat identity json-opts)))

(defmethod hato-mw/coerce-response-body :json [{:keys [coerce] :as req}
                                               {:keys [body status] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (cond
      (and (hato-mw/unexceptional-status? status)
           (or (nil? coerce) (= coerce :unexceptional)))
      (with-open [r (io/reader body :encoding charset)]
        (assoc resp :body (json/read r :key-fn keyword)))

      (= coerce :always)
      (with-open [r (io/reader body :encoding charset)]
        (assoc resp :body (json/read r :key-fn keyword)))

      (and (not (hato-mw/unexceptional-status? status)) (= coerce :exceptional))
      (with-open [r (io/reader body :encoding charset)]
        (assoc resp :body  (json/read r :key-fn keyword)))

      :else (assoc resp :body (slurp body :encoding charset)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection

(defprotocol IConnection
  (path-url [_ p])
  (request [_ method url opts]))

(defrecord Connection [client endpoint token cache]
  IConnection
  (path-url [this p]
    (if (vector? p)
      (recur (str/join "/" (map as-str p)))
      (str endpoint p)))
  (request [this method path opts]
    (into {:http-client client
           :request-method method
           :url (path-url this path)
           :headers {"x-metabase-session" token}
           :content-type :json
           :as :json}
          opts)))

(defn connect [{:keys [user password
                       ;; optional
                       host port https? hato-client connect-timeout]
                :or {connect-timeout 10000
                     https? false
                     host "localhost"
                     port 3000}}]
  (let [client (or hato-client
                   (http/build-http-client
                    {:connect-timeout connect-timeout
                     :redirect-policy :always}))
        endpoint (str "http" (when https? "s") "://" host ":" port "/api/")
        token (:id (:body (http/post (str endpoint "session") {:http-client client
                                                               :form-params {"username" user
                                                                             "password" password}
                                                               :content-type :json
                                                               :as :json})))]
    (map->Connection {:client client
                      :endpoint endpoint
                      :token token
                      :cache (atom {})})))

(defn mb-get [conn path & [opts]] (http/request (request conn :get path opts)))
(defn mb-post [conn path & [opts]] (http/request (request conn :post path opts)))
(defn mb-put [conn path & [opts]] (http/request (request conn :put path opts)))
(defn mb-delete [conn path & [opts]] (http/request (request conn :delete path opts)))


(defn find-database [client name]
  (some #(when (= name (:name %)) %) (:body (mb-get client [:database]))))

(defn format-sql [sql]
  (first (honey/format sql :parameterizer :none)))

(defn native-query [{:keys [database sql display variables]
                     :or {display "table"}
                     :as opts}]
  {:name (:name opts)
   :database_id database
   :query_type "native"
   :dataset_query {:database database
                   :type "native"
                   :native
                   (cond-> {:query (if (map? sql) (format-sql sql) sql)}
                     (seq variables)
                     (assoc :template-tags
                            (into {}
                                  (map (fn [[varname opts]]
                                         [varname (merge
                                                   {:id (rand-id)
                                                    :name (as-str varname)
                                                    :display-name (as-str varname)
                                                    :type "text"}
                                                   opts)]))
                                  variables)))}
   :display (as-str display)
   :visualization_settings {}})


(defn display-as [card type]
  (assoc card :display (as-str type)))

(defn viz-settings [card settings]
  (update card :visualization_settings
          (fn [viz]
            (reduce (fn [viz [kk vs]]
                      (reduce (fn [viz [k v]]
                                (assoc viz
                                       (keyword (str (as-str kk) "." (as-str k)))
                                       v))
                              viz
                              vs))
                    viz
                    settings))))

(comment
  (def ccc (connect {:user "admin@example.com"
                     :password "secret1"}))

  (mb-get ccc [:user :current])
  (:body   (mb-get ccc [:card]))
  (:body (mb-get ccc [:database]))


  (json/write-str card-edn)

  (path-url ccc "x")

  (def sql "SELECT * FROM onze.company")

  (mb-post ccc [:card] {:form-params (native-query-card-data
                                      {:name "my card"
                                       :database 2
                                       :query sql})})
  (:body (mb-post ccc
                  [:card]
                  {:form-params
                   (-> (native-query {:name "account bars var"
                                      :database 2
                                      :sql {:select ["account__name" "amount"]
                                            :from ["onze.journal_entry_line"]
                                            :where [:= "account__name" "{{account}}"]}
                                      :variables {:account {}}
                                      :display :bar})
                       (viz-settings {:graph {:dimensions ["account__name"]
                                              :metrics ["amount"]}}))}))


  (let [v1 (:native (:dataset_query (dissoc (:body (mb-get ccc [:card 4])) :result_metadata)))
        v2 (:native (:dataset_query (dissoc (:body (mb-get ccc [:card 6])) :result_metadata)))]
    (into {}
          (keep (fn [k]
                  (when (not= (v1 k) (v2 k))
                    [k [(v1 k) (v2 k)]])))
          (into (set (keys v1)) (keys v2))))


  (map :description (:body(mb-get ccc [:database])))
  (last (butlast (:body(mb-get ccc [:card]))))


  (def card-edn
    {:name "Accounts Payable - Company Filter"
     :description "Query created via the API"
     :visualization_settings {:table.pivot_column "description", :table.cell_column "balance"}
     :database_id (:id (find-database ccc "datomic"))
     :display "table"
     :query_type "native"
     :dataset_query
     {:database (:id (find-database ccc "datomic"))
      :type "native"
      :native
      {:template_tags {:company
                       {:id (str (gensym))
                        :name "company"
                        :display_name "Company"
                        :type "text"
                        :required true}
                       ;; We can do "smart" filters that allow a pre-populated
                       ;; choice, but then we have to deal with MB's internal
                       ;; table/column ids
                       #_:fiscal_year
                       #_{:id (str (gensym))
                          :name "fiscal_year",
                          :display_name "Fiscal year"
                          :type "dimension"
                          :dimension ["field-id" 1616] ;; onze.company_x_fiscal_year.id
                          :widget_type "id"
                          :required true}}
       :query "SELECT
          company_x_fiscal_years.id, company_x_fiscal_years.display_name,
          account.number, account.description,
          SUM(IF(flow = (SELECT db__id
                   FROM onze.db__idents
                   WHERE ident = ':flow/credit'),
           amount,
           (amount * -1))) AS balance
        FROM onze.journal_entry_line
        JOIN onze.account ON onze.account.db__id = onze.journal_entry_line.account
        JOIN onze.journal_entry_x_journal_entry_lines ON onze.journal_entry_x_journal_entry_lines.journal_entry_lines = onze.journal_entry_line.db__id
        JOIN onze.ledger_x_journal_entries            ON onze.ledger_x_journal_entries.journal_entries                = onze.journal_entry_x_journal_entry_lines.db__id
        JOIN onze.fiscal_year_x_ledgers               ON onze.fiscal_year_x_ledgers.ledgers                           = onze.ledger_x_journal_entries.db__id
        JOIN onze.company_x_fiscal_years              ON onze.company_x_fiscal_years.fiscal_years                     = onze.fiscal_year_x_ledgers.db__id
        WHERE onze.account.subtype = (SELECT db__id
                                      FROM onze.db__idents
                                      WHERE ident = ':account.subtype/accounts-payable')
        AND company_x_fiscal_years.id = {{company}}
        AND {{fiscal_year}}
        GROUP BY company_x_fiscal_years.id, company_x_fiscal_years.display_name, account.number, account.description
        ORDER BY balance DESC
        LIMIT 10"}}}))
