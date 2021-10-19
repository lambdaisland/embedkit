(ns lambdaisland.automation
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
  [base-url setup-token user password]
  (let [setup-url (str base-url "/api/setup")
        data {:token setup-token
              :user {:email user :password password
                     :first_name "lambdaisland.com" :last_name "gaiwan.co"}
              :prefs {:site_name "Metabase BI"}}
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
    (when (= 200 status)
      (json/read-str body
                     :key-fn keyword))))

(defn get-metabase-setting-by-key!
  "get metabase setting using /api/setting GET API"
  [e-conn key]
  (let [resp (embedkit/mb-get e-conn "/api/setting/")
        kv-pairs (filterv #(= (:key %) key) (:body resp))]
    (:value (first kv-pairs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; init API

(defn init-metabase!
  "Doing the following things:

   1. create the first user
   2. enable embedding"
  [{:keys [user password
           ;; optional
           https? host port]
    :or {https? false
         host "localhost"
         port 3000}}]
  (let [base-url (metabase-endpoint https? host port)
        setup-token (get-metabase-setup-token! base-url)
        session-key (create-admin-user! base-url setup-token user password)]
    (enable-embedding! base-url session-key)))

(defn get-embedding-secret-key
  "retrive the embedding-secret-key"
  [e-conn]
  (get-metabase-setting-by-key! e-conn "embedding-secret-key"))

(defn create-presto-db!
  "Create the presto-db in metabase if it does not exist"
  [e-conn presto-db-name]
  (when (nil? (embedkit/find-database e-conn presto-db-name))
    (do
      (embedkit/mb-post
       e-conn
       "/api/database"
       {:form-params {:name presto-db-name
                      :engine "presto"
                      :details
                      {:host "localhost"
                       :port 4383
                       :catalog "analytics"
                       :user "."
                       :password ""
                       :ssl false
                       :tunnel-enabled false}}}))))
