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
        (assoc resp :body (json/read r
                                     :key-fn keyword
                                     :eof-error? false)))

      (= coerce :always)
      (with-open [r (io/reader body :encoding charset)]
        (assoc resp :body (json/read r
                                     :key-fn keyword
                                     :eof-error? false)))

      (and (not (hato-mw/unexceptional-status? status)) (= coerce :exceptional))
      (with-open [r (io/reader body :encoding charset)]
        (assoc resp :body  (json/read r
                                      :key-fn keyword
                                      :eof-error? false)))

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

(defn do-request [req]
  (vary-meta (http/request req)
             assoc
             ::request req))

(defn mb-get [conn path & [opts]]
  (do-request (request conn :get path opts)))

(defn mb-post [conn path & [opts]]
  (do-request (request conn :post path opts)))

(defn mb-put [conn path & [opts]]
  (do-request (request conn :put path opts)))

(defn mb-delete [conn path & [opts]]
  (do-request (request conn :delete path opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- format-sql [sql]
  (first (honey/format sql :parameterizer :none)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database operations

(defn find-database [client db-name]
  (some #(when (= db-name (:name %)) %) (:body (mb-get client [:database]))))

(defn database-fields [client db-id]
  (let [tables (-> client
                   (mb-get [:database db-id]
                           {:query-params {:include "tables.fields"}})
                   (get-in [:body :tables]))]
    (mapcat (fn [{:keys [schema name fields]}]
              (map (fn [{:keys [id] field-name :name}]
                     [schema name field-name id])
                   fields))
            tables)))

(defn database-id [conn db-name]
  (let [path [:databases db-name :id]]
    (if-let [id (get-in @(:cache conn) path)]
      id
      (let [db (find-database conn db-name)]
        (swap! (:cache conn) assoc-in path (:id db))
        (:id db)))))

(defn field-id [conn db-name schema table field]
  (let [path [:databases db-name
              :schemas schema
              :tables table
              :fields field
              :id]]
    (if-let [id (get-in @(:cache conn) path)]
      id
      (let [db-id (database-id conn db-name)
            fields (database-fields conn db-id)]
        (swap! (:cache conn)
               (fn [cache]
                 (reduce (fn [c [s t f id]]
                           (assoc-in c
                                     [:databases db-name
                                      :schemas s
                                      :tables t
                                      :fields f
                                      :id] id))
                         cache fields)))
        (get-in @(:cache conn) path)))))

(defn- response-body [response]
  (vary-meta (:body response)
             assoc
             ::request (::request (meta response))
             ::response response))

(defn path [{::keys [type dashboard-id] :keys [id]}]
  (cond-> (case type
            :card [:card]
            :dashboard [:dashboard]
            :dashboard-card [:dashboard dashboard-id :cards])
    id (conj id)))

(defn create! [conn entity]
  (response-body (mb-post conn
                          (path entity)
                          {:form-params (dissoc entity ::type ::dashboard-id)})))

(defn enable-embedding! [conn dashboard]
  (response-body (mb-put conn
                         [:dashboard (:id dashboard)]
                         {:form-params
                          {:enable-embedding true
                           #_:embedding-params
                           #_{:company "enabled"
                              :fy "enabled"}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data functions

(defn native-query
  "Data definition of a native query

  - `:name` Name for this query
  - `:database` The metabase DB id
  - `:sql` The native query, as string or as honeysql query map. Can contain `{{variable}}` placeholders.
  - `:display` How to visualize the result, e.g. `\"table\"`
  - `:variables` Variables used in the native query, as a map from varname to options map
  "
  [{:keys [name database sql display variables]
    :or {display "table"}
    :as opts}]
  {::type :card
   :name name
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

(defn viz-settings
  "Set a card's visualization settings, takes nested maps."
  [card settings]
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

(defn bar-chart
  "Display as a bar chart, provide at a minimum the `x-axis`/`y-axis` dimensions."
  [qry {:keys [x-axis y-axis
               x-label y-label
               log?
               stacked?]}]
  (assert x-axis)
  (assert y-axis)
  (-> qry
      (assoc :display "bar")
      (viz-settings (cond-> {:graph {:dimensions (cond-> x-axis
                                                   (not (coll? x-axis))
                                                   vec)
                                     :metrics (cond-> y-axis
                                                (not (coll? y-axis))
                                                vec)}}
                      x-label
                      (assoc-in [:graph :x_axis :title_text] x-label)
                      y-label
                      (assoc-in [:graph :x_axis :title_text] y-label)
                      log?
                      (assoc-in [:graph :y_axis :scale] "log")
                      stacked?
                      (assoc-in [:stackable :stack_type] "stacked")))))

(defn dashboard [{:keys [name description variables]}]
  {::type :dashboard
   :name name
   :description description
   #_#_:parameters [{:name "Company"
                     :slug "company"
                     :id (str (gensym))
                     :type "category"}]})

(defn dashboard-card [{:keys [card card-id x y width height
                              dashboard dashboard-id]
                       :or {width 10 height 10}}]
  (cond-> {::type :dashboard-card
           ::dashboard-id (or dashboard-id (:id dashboard))
           :cardId (or card-id (:id card))
           :sizeX width
           :sizeY height
           #_#_                 :parameter-mappings
           [{:parameter-id (get-in dashboard [:parameters 0 :id])
             :card-id (:id mb-card)
             :target ["variable" ["template-tag" "company"]]}
            {:parameter-id (get-in dashboard [:parameters 1 :id])
             :card-id (:id mb-card)
             :target ["dimension" ["template-tag" "fiscal_year"]]}]}
    y (assoc :row y)
    x (assoc :col x)))
