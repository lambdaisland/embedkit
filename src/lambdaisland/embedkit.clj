(ns lambdaisland.embedkit
  (:require [buddy.sign.jwt :as jwt]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hasch.core :as hasch]
            [hasch.hex :as hasch-hex]
            [hato.client :as http]
            [hato.middleware :as hato-mw]
            [honeysql.core :as honey]
            [lambdaisland.uri :as uri])
  (:import (java.util UUID)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- hex->base36 [^String hex]
  (.toString (BigInteger. hex 16) 36))

(defn- edn->hash [edn]
  (let [edn (walk/postwalk (fn [x]
                             (if (and x (str/starts-with? (.getName ^Class (class x)) "java.time"))
                               (str x)
                               x))
                           edn)]
    (hex->base36 (hasch-hex/encode (take 32 (hasch/edn-hash edn))))))

(defn- as-str [v]
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
        (assoc resp :body (json/read r
                                     :key-fn keyword
                                     :eof-error? false)))

      :else (assoc resp :body (slurp body :encoding charset)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection

(defprotocol IConnection
  (path-url [_ p])
  (request [_ method url opts])
  (conn-cache [_])
  (secret-key [_]))

(defrecord Connection [client endpoint token cache secret-key middleware]
  IConnection
  (path-url [this p]
    (if (vector? p)
      (recur (str (if (= :embed (first p)) "/" "/api/")
                  (str/join "/" (map as-str p))))
      (str endpoint p)))
  (request [this method path opts]
    (-> {:http-client client
         :request-method method
         :url (path-url this path)
         :headers {"x-metabase-session" token}
         :content-type :json
         :as :json}
        (cond-> middleware (assoc :middleware middleware))
        (into opts)))
  (conn-cache [this]
    (:cache this))
  (secret-key [this]
    (:secret-key this)))

(extend-protocol IConnection
  clojure.lang.Atom
  (path-url [this p]
    (path-url @this p))
  (request [this method path opts]
    (request @this method path opts))
  (conn-cache [this]
    (conn-cache @this))
  (secret-key [this]
    (secret-key @this)))

(defn connect
  "Create a connection to the Metabase API. This does an authentication call to
  get a token that is used subsequently. Returns a Connection which wraps a Hato
  HttpClient instance, endpoint details, the token, and a cache atom to reduce
  requests.

  Defaults to connecting to `http://localhost:3000`

  To create embed urls a `:secret-key` is also needed, to be found in the
  Metabase embed settings."
  [{:keys [user password
           ;; optional
           host port https? hato-client connect-timeout
           secret-key middleware]
    :or {connect-timeout 10000
         https? false
         host "localhost"
         port 3000}
    :as conn-opts}]
  (when-not secret-key
    (binding [*out* *err*]
      (println "WARNING: no :secret-key provided to" `connect ", generating embed urls will be disabled.")))
  (let [client (or hato-client
                   (http/build-http-client
                    {:connect-timeout connect-timeout
                     :redirect-policy :always}))
        endpoint (str "http" (when https? "s") "://" host (when port (str ":" port)))
        token (:id (:body (http/post (str endpoint "/api/session")
                                     {:http-client client
                                      :form-params {"username" user
                                                    "password" password}
                                      :content-type :json
                                      :as :json})))]
    (map->Connection (assoc
                       conn-opts
                       :client client
                       :endpoint endpoint
                       :token token
                       :cache (atom {})
                       :secret-key secret-key
                       :middleware middleware))))

(defn- do-request [req]
  (vary-meta (http/request req)
             assoc
             ::request req))

(defmacro with-refresh-auth [conn & body]
  `((fn retry# [conn# retries#]
      (Thread/sleep (* 1000 retries# retries#)) ; backoff
      (try
        ~@body
        (catch clojure.lang.ExceptionInfo e#
          (if (and (= 401 (:status (ex-data e#)))
                   (instance? clojure.lang.Atom conn#)
                   (< retries# 4))
            (do
              (reset! conn# (connect @conn#))
              (retry# conn# (inc retries#)))
            (throw e#)))))
    ~conn
    0))

(defn mb-request [verb conn path opts]
  (with-refresh-auth conn
    (do-request (request conn verb path opts))))

(defn mb-get
  "Perform a GET request to the Metabase API. Path can be a string or a vector.

  (mb-get conn \"/api/cards/6\")
  (mb-get conn [:cards 6])"
  [conn path & [opts]]
  (mb-request :get conn path opts))

(defn mb-post
  "Perform a POST request to the Metabase API"
  [conn path & [opts]]
  (mb-request :post conn path opts))

(defn mb-put
  "Perform a PUT request to the Metabase API"
  [conn path & [opts]]
  (mb-request :put conn path opts))

(defn mb-delete
  "Perform a DELETE request to the Metabase API"
  [conn path & [opts]]
  (mb-request :delete conn path opts))

(defn embed-payload-url
  "Sign the payload with the `:secret-key` from the connection, and use it to
  request an embed-url."
  [conn payload {:keys [bordered? titled? filters]
                 :or {bordered? false
                      titled? false
                      filters {}}
                 :as opts}]
  (when-let [key (secret-key conn)]
    (let [token (jwt/sign payload key)]
      (-> (uri/uri (path-url conn [:embed :dashboard token]))
          (assoc :fragment (uri/map->query-string
                            (assoc filters
                                   :bordered (str bordered?)
                                   :titled (str titled?))))
          str))))

(defn embed-url
  "Get an embedding URL for a given resource."
  ([conn resource]
   (embed-url conn resource nil))
  ([conn resource {:keys [variables bordered? titled? filters ^long timeout]
                   :or {timeout 3600
                        variables {}}
                   :as opts}]
   (let [{::keys [type] :keys [id]
          :or {type :dashboard}} (if (map? resource) resource {:id resource})]
     (embed-payload-url conn
                        {:resource {type id}
                         :params   variables
                         :exp      (+ (long (/ (System/currentTimeMillis) 1000))
                                      timeout)}
                        opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- format-sql [sql]
  (first (honey/format sql :parameterizer :none)))

(defmacro with-cache [conn path & body]
  `(or (get-in @(conn-cache ~conn) ~path)
       (let [res# (do ~@body)]
         (swap! (conn-cache ~conn) assoc-in ~path res#)
         res#)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pagination

(def default-pagination-size 200)

(defn wrap-paginate
  "Decorator of the normal `mb-get` function
   This decorator will create a new version of `mb-get` which will collect all
    the results of each page and return the concatenated list of `[:body :data]`

   Note: `error-logger` can be nil"
  ([f page-size]
   (wrap-paginate f page-size nil))
  ([f page-size error-logger]
   {:pre [(pos-int? page-size)]}
   (fn paginate
     ([conn path]
      (paginate conn path {:query-params {}}))
     ([conn path opts]
      (let [limit page-size
            opts* (update opts
                          :query-params #(conj % [:limit limit] [:offset 0]))
            resp* (f conn path opts*)
            total (get-in resp* [:body :total])
            lazy-f (fn lazy-f [{:keys [status] :as resp} offset]
                     (if (= status 200)
                       (lazy-cat
                        (get-in resp [:body :data])
                        (when (<= (* (inc offset) limit) total)
                          (lazy-f
                           (f conn path (assoc-in opts* [:query-params :offset] offset)) (inc offset))))
                       (when error-logger
                         (error-logger resp))))]
        (lazy-f resp* 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database operations

(defn find-database
  "Get a Database resource by name. This means fetching all databases, but the
  result is cached, so we only re-fetch if the given db-name is not yet present
  in the cache.

  Mainly used for finding the numeric id of the given Database."
  [conn db-name]
  (let [path [:databases db-name]]
    (or (get-in @(conn-cache conn) path)
        (do
          (swap! (conn-cache conn)
                 update
                 :databases
                 (fnil into {})
                 (doto (map (juxt :name identity)
                            (get-in (mb-get conn [:database]) [:body :data]))))
          (get-in @(conn-cache conn) path)))))

(defn fetch-database-fields
  "Get all tables/fields for a given db-id. Always does a request."
  [client db-id]
  (let [tables (-> client
                   (mb-get [:database db-id]
                           {:query-params {:include "tables.fields"}})
                   (get-in [:body :tables]))
        field-index (mapcat (fn [{:keys [schema name fields]}]
                              (map (fn [{:keys [id] field-name :name}]
                                     [schema name field-name id])
                                   fields))
                            tables)
        table-index (map (juxt :schema :name identity) tables)]
    {:field-index field-index
     :table-index table-index}))

(defn table-id
  "Find the numeric id of a given table in a database/schema. Leverages the
  cache."
  [conn db-name schema table]
  (let [path [:databases db-name
              :schemas schema
              :tables table
              :id]]
    (if-let [id (get-in @(conn-cache conn) path)]
      id
      (let [db-id (:id (find-database conn db-name))
            tables (:table-index (fetch-database-fields conn db-id))]
        (swap! (conn-cache conn)
               (fn [cache]
                 (reduce (fn [c [schema-name table-name table-entity]]
                           (assoc-in c
                                     [:databases db-name
                                      :schemas schema-name
                                      :tables table-name
                                      :id] (:id table-entity)))
                         cache tables)))
        (get-in @(conn-cache conn) path)))))

(defn field-id
  "Find the numeric id of a given field in a database/schema/table. Leverages the
  cache."
  [conn db-name schema table field]
  (let [path [:databases db-name
              :schemas schema
              :tables table
              :fields field
              :id]]
    (if-let [id (get-in @(conn-cache conn) path)]
      id
      (let [db-id (:id (find-database conn db-name))
            fields (:field-index (fetch-database-fields conn db-id))]
        (swap! (conn-cache conn)
               (fn [cache]
                 (reduce (fn [c [s t f id]]
                           (assoc-in c
                                     [:databases db-name
                                      :schemas s
                                      :tables t
                                      :fields f
                                      :id] id))
                         cache fields)))
        (get-in @(conn-cache conn) path)))))

(defn fetch-all-users
  "Get users. Always does a request."
  [client]
  (let [query-opts {:include_deactivated "true"}
        f (wrap-paginate mb-get default-pagination-size)
        user-list (-> client
                      (f [:user]
                         {:query-params query-opts}))]
    (vec user-list)))

(defn fetch-users
  "Get users:

   When option is :all, then get all the users. Useful for getting all the users.
   When option is :active, then get only the active users."
  [client option]
  (let [query-opts (case option
                     :all {:include_deactivated "true"}
                     :active {})
        f (wrap-paginate mb-get default-pagination-size)
        user-list (-> client
                      (f [:user]
                         {:query-params query-opts}))]
    user-list))

(defn user-id
  "Find the numeric id of a given user email Leverages the cache."
  [conn email]
  (let [path [:user-email email
              :id]]
    (if-let [id (get-in @(conn-cache conn) path)]
      id
      (let [users (fetch-users conn :all)]
        (swap! (conn-cache conn)
               (fn [cache]
                 (reduce (fn [c {:keys [email id]}]
                           (assoc-in c
                                     [:user-email email
                                      :id] id))
                         cache users)))
        (get-in @(conn-cache conn) path)))))

(defn fetch-all-groups
  "Get groups. Always does a request."
  [client]
  (let [group (-> client
                  (mb-get [:permissions :group])
                  (get-in [:body]))]
    group))

(defn group-id
  "Find the numeric id of a given group name. Leverages the cache."
  [conn name]
  (let [path [:group-name name
              :id]]
    (if-let [id (get-in @(conn-cache conn) path)]
      id
      (let [groups (fetch-all-groups conn)]
        (swap! (conn-cache conn)
               (fn [cache]
                 (reduce (fn [c {:keys [name id]}]
                           (assoc-in c
                                     [:group-name name
                                      :id] id))
                         cache groups)))
        (get-in @(conn-cache conn) path)))))

(defn trigger-db-fn!
  "When success, return ... "
  [conn db-name db-fn-link]
  {:pre [(or (#{"sync_schema" "rescan_values"} db-fn-link)
             (#{:sync_schema :rescan_values} db-fn-link))]}
  (let [db-id (:id (find-database conn db-name))
        resp (-> conn
                 (mb-post [:database db-id (keyword db-fn-link)])
                 (get-in [:body]))]
    resp))

(def embedkit-keys
  "Keys that we add interally to entity maps. Generally we deal with data exactly
  as the Metabase API expects it and returns, but we add these namespaced keys
  for additional bookkeeping. "
  [;; The entity type: :card, :dashboard, :dashboard-card
   ::type
   ;; For a dashboard-card, the dashboard to add it to
   ::dashboard-id
   ;; For a dashboard-card, the card that it links to
   ::card
   ;; For a dashboard, the dashboard-cards it should contain
   ::dashboard-cards
   ;; For a card, its variables (map from keyword to options map)
   ::variables])

(defn- response-body [response]
  (vary-meta (:body response)
             assoc
             ::request (::request (meta response))
             ::response response))

(defn- add-hash [entity hash]
  (case (::type entity)
    :card
    (assoc-in entity [:visualization_settings :embedkit.hash] hash)
    :dashboard
    (assoc entity :description hash)
    entity))

(defn- strip-embedkit-keys [entity]
  (apply dissoc entity embedkit-keys))

(defn- restore-embedkit-keys [response entity]
  (merge response (select-keys entity embedkit-keys)))

(defn resource-path
  "Get the API path (in vector representation) for a given resource."
  [{::keys [type dashboard-id] :keys [id]}]
  (cond-> (case type
            :card [:card]
            :dashboard [:dashboard]
            :dashboard-card [:dashboard dashboard-id :cards])
    id (conj id)))

(defn create-one!
  "Create an entity in Metabase, based on the EDN description. This is a low level
  operation, which bypasses caching, and deals with a single entity at a time.
  The recommended API is [[find-or-create!]], which handles caching, and
  creating multiple related entities.

  `entity` is the result of one of the data definition functions
  like [[card]], [[dashboard]], etc."
  [conn entity]
  (-> (mb-post conn
               (resource-path entity)
               {:form-params (strip-embedkit-keys entity)})
      (response-body)
      (restore-embedkit-keys entity)))

(defn populate-cache
  "Fetch all existing cards and databases from Metabase, and add them to the
  per-connection in-memory content addressed cache. Do this after creating your
  connection but before calling [[find-or-create!]], to limit the number of
  duplicate entities that are created. With a warmed up cache you can safely
  call [[find-or-create!]] repeatedly with the same arguments without fear of
  causing an explosion of entities in Metabase."
  [conn]
  (doseq [card (:body (mb-get conn [:card]))]
    (when-let [hash (get-in card [:visualization_settings :embedkit.hash])]
      (swap! (conn-cache conn) assoc-in [:by-hash hash] (assoc card ::type :card))))

  (doseq [db (:body (mb-get conn [:dashboard]))]
    (when-let [hash (:description db)]
      (swap! (conn-cache conn) assoc-in [:by-hash hash] (assoc db ::type :dashboard)))))

(defn- find-or-create-one!
  [conn entity]
  (let [hash (edn->hash entity)]
    (with-cache conn [:by-hash hash]
      (create-one! conn (add-hash entity hash)))))

(defn find-or-create!
  "Idempotently turn an EDN description into one or more Metabase entities.
  Combine this function with the result from [[dashboard]] or [[card]] to do the
  heavy lifting.

  This may create multiple entities, creating a Dashboard will also create (or
  reuse) Card and DashboardCard entities, and wire them up appropriately.

  Entities are cached in a content-addressed in-memory cache, so calling this
  twice with the same EDN will immediately return the previously created entity.

  Only the the in-memory cache is checked, so calling this on a freshly created
  connection will always end up creating new entities. It's up to you to call
  call [[populate-cache]] after creating your connection, or on a regular basis,
  so you can re-use what is already in Metabase.

  If there is a cache miss a new entity will be created, even though there may
  be an entity in Metabase with the same hash that we are simply not aware of.
  As such this function only attempts to reuse entities, it does not guarantee
  it."
  [conn {::keys [card dashboard-cards] :as entity}]
  (let [hash (edn->hash entity)]
    (with-cache conn [:by-hash hash]
      ;; Dashboard-card with a card, create/find the card first
      (let [entity (if card
                     (let [card-id (or (:id card)
                                       (:id (find-or-create! conn card)))]
                       (-> entity
                           (assoc :cardId card-id)
                           (update :parameter_mappings (partial map #(assoc % :card_id card-id)))))
                     entity)
            ;; create the entity itself
            response (create-one! conn (add-hash entity hash))]
        ;; :enable_embedding is not respected on the initial POST, it has to be
        ;; set after the dashboard is created.
        (when (:enable_embedding entity)
          (mb-put conn
                  (resource-path (assoc entity :id (:id response)))
                  {:form-params (strip-embedkit-keys entity)}))
        ;; Dashboard with dashboard-cards
        (when dashboard-cards
          (run! #(find-or-create! conn (assoc % ::dashboard-id (:id response)))
                dashboard-cards))
        response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data functions

(defn native-card
  "Data definition of a native query. Returns a Card resource that can be passed to `create!`.

  - `:name` Name for this query
  - `:database` The metabase DB id
  - `:sql` The native query, as string or as honeysql query map. Can contain `{{variable}}` placeholders.
  - `:display` How to visualize the result, e.g. `\"table\"`
  - `:variables` Variables used in the native query, as a map from varname to options map
  "
  [{:keys [name database sql display variables]
    :or {display "table"}
    :as opts}]
  (let [variables (into {}
                        (map (fn [[varname opts]]
                               [varname (merge
                                         {:id (as-str varname)
                                          :name (as-str varname)
                                          :display-name (as-str varname)
                                          :type "text"}
                                         opts)]))
                        variables)]
    {::type :card
     :name name
     :database_id database
     :query_type "native"
     :dataset_query {:database database
                     :type "native"
                     :native
                     (cond-> {:query (if (map? sql) (format-sql sql) sql)}
                       (seq variables)
                       (assoc :template-tags variables))}
     :display (as-str display)
     :visualization_settings {}
     ::variables variables}))

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
  "Display as a bar chart, provide at a minimum the `x-axis`/`y-axis` dimensions.
  Takes/returns a Card entity."
  [card {:keys [x-axis y-axis
                x-label y-label
                log?
                stacked?]}]
  (assert x-axis)
  (assert y-axis)
  (-> card
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

(defn dashboard-card
  "Data definition of a DashboardCard entity"
  [{:keys [card card-id x y width height
           dashboard dashboard-id]
    :or {width 10 height 10
         x 0 y 0}}]
  (let [card-id (or card-id (:id card))]
    (cond-> {::type :dashboard-card
             ::dashboard-id (or dashboard-id (:id dashboard))
             ::card card
             :cardId card-id
             :size_x width
             :size_y height
             :parameter_mappings (for [[var-name {:keys [id]}] (::variables card)]
                                   {:parameter_id id
                                    :target ["variable" ["template-tag" id]]
                                    ;; Likely still nil here, unless the card has
                                    ;; already been created. Will get set during
                                    ;; creation
                                    :card_id card-id})}
      y (assoc :row y)
      x (assoc :col x))))

(defn dashboard
  "Data definition of a Dashboard entity."
  [{:keys [name cards]}]
  (let [variables (apply merge (map (comp ::variables :card) cards))]
    {::type :dashboard
     :name name
     :enable_embedding true
     :embedding_params (reduce (fn [m {:keys [name editable?]}]
                                 (assoc m name (if editable? "enabled" "locked")))
                               {}
                               (vals variables))
     ::dashboard-cards (map dashboard-card cards)
     :parameters (for [{:keys [id name]} (vals variables)]
                   {:id id
                    :slug name
                    :name name
                    :type "id"})}))
