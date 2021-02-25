(ns lambdaisland.embedkit.repl
  "REPL helpers"
  (:require [clojure.java.browse :as browse]
            [clojure.string :as str]
            [lambdaisland.embedkit :as e]))

(defn delete-all-cards!
  "Clean up after experimenting. 'Card' here means metabase query, they use the
  terms interchangably"
  [conn]
  (doseq [{:keys [id]} (:body (e/mb-get conn [:card]))]
    (println "Deleting card" id)
    (e/mb-delete conn [:card id])))

(defn delete-all-dashboards!
  [conn]
  (doseq [{:keys [id]} (:body (e/mb-get conn [:dashboard]))]
    (println "Deleting dashboard" id)
    (e/mb-delete conn [:dashboard id])))

(defn browse! [res]
  (browse/browse-url (str "http://localhost:3000/"
                          (cond
                            (= :card (::e/type res))
                            "question"
                            (= :dashboard (::e/type res))
                            "dashboard")
                          "/"
                          (:id res))))
(defn print-request-mw
  "Very basic Hato middleware to see which requests are being made"
  [c]
  (fn
    ([req]
     (println (:request-method req) (:url req))
     (c req))
    ([req resp raise]
     (println (:request-method req) (:url req))
     (c req resp raise))))
