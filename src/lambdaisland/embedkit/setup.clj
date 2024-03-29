(ns lambdaisland.embedkit.setup
  "The automation helpers to automate the init setup of metabase"
  (:require [lambdaisland.embedkit :as embedkit]
            [clojure.data.json :as json]
            [hato.client :as http]))

(defn metabase-endpoint
  "Create the base url of metabase"
  [https? host port]
  (str "http" (when https? "s") "://" host (when port (str ":" port))))

(defn get-metabase-setup-token!
  "Get the metabase setup token by using /api/session/properties GET API"
  [base-url]
  (let [session-url (str base-url "/api/session/properties")
        {:keys [status body]} (http/request {:method :get
                                             :url session-url
                                             :content-type :json})]
    (when (= 200 status)
      (:setup-token (json/read-str body
                                   :key-fn keyword)))))

(defn create-admin-user!
  "Create the first/admin user of the metabase, and get the session-key"
  [{:keys [base-url setup-token email password
           first-name last-name site-name]}]
  (let [setup-url (str base-url "/api/setup")
        data {:token setup-token
              :user {:email email :password password
                     :first_name first-name :last_name last-name}
              :prefs {:site_name site-name}}
        {:keys [status body]} (http/request {:method :post
                                             :url setup-url
                                             :content-type :json
                                             :form-params data})]
    (when (= 200 status)
      (:id (json/read-str body
                          :key-fn keyword)))))

(defn enable-embedding!
  "change the metabase setting using /api/setting PUT API to enable embedding"
  [base-url session-key]
  (let [setting-url (str base-url "/api/setting/enable-embedding")
        data {:key "enable-embedding"
              :value true}
        {:keys [status body]} (http/request {:headers {"x-metabase-session" session-key}
                                             :method :put
                                             :url setting-url
                                             :content-type :json
                                             :form-params data})]
    (if (= 200 status)
      (json/read-str body
                     :key-fn keyword)
      (prn {:api-endpoint "/api/setting/enable-embedding"
            :status status
            :body body}))))

(defn get-metabase-setting!
  "get metabase setting using /api/setting GET API"
  ([e-conn]
   (let [resp (embedkit/mb-get e-conn "/api/setting/")]
     (:body resp)))
  ([e-conn key]
   (let [resp (embedkit/mb-get e-conn "/api/setting/")
         kv-pairs (filterv #(= (:key %) key) (:body resp))]
     (first kv-pairs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; init API

(defn init-metabase!
  "Doing the following things:

   1. create the first user
   2. enable embedding"
  [{:keys [user password
           ;; optional
           first-name last-name site-name
           https? host port]
    :or {first-name "lambdaisland.com"
         last-name "gaiwan.co"
         site-name "Metabase BI"
         https? false
         host "localhost"
         port 3000}}]
  (let [base-url (metabase-endpoint https? host port)
        setup-token (get-metabase-setup-token! base-url)
        session-key (create-admin-user! {:base-url base-url
                                         :setup-token setup-token
                                         :email user
                                         :password password
                                         :first-name first-name
                                         :last-name last-name
                                         :site-name site-name})]
    (comment (prn {:base-url base-url
                   :setup-token setup-token
                   :session-key session-key}))
    (enable-embedding! base-url session-key)))

(defn get-embedding-secret-key
  "retrive the embedding-secret-key"
  [e-conn]
  {:pre [(satisfies? embedkit/IConnection e-conn)]}
  (get-metabase-setting! e-conn "embedding-secret-key"))

(defn create-db!
  "Create the database in metabase if it does not exist"
  [e-conn db-conn-name engine details]
  {:pre [(satisfies? embedkit/IConnection e-conn) (string? db-conn-name) (string? engine) (map? details)]}
  (if (nil? (embedkit/find-database e-conn db-conn-name))
    (embedkit/mb-post
     e-conn
     "/api/database"
     {:form-params {:name db-conn-name
                    :engine engine
                    :details details}})
    (prn "duplicated db-conn-name " db-conn-name "already exists.")))
