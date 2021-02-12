(ns lambdaisland.embedkit
  (:require [hato.client :as http]
            [hato.middleware :as hato-mw]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

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
      (recur (str/join "/" (map name p)))
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


(comment
  (def ccc (connect {:user "admin@example.com"
                     :password "secret1"}))

  (mb-get ccc [:user :current])

  (path-url ccc "x"))
