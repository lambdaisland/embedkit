(ns pagination
  (:require [lambdaisland.embedkit :as e]))

(def conn
  (e/connect (read-string (slurp "dev/config.edn"))))

(e/fetch-users conn :all)
(e/fetch-users conn :active)

(e/find-database conn "example_tenant")

(comment
  (get-in (e/mb-get conn [:database]
                    {:query-params {:limit 1
                                    :offset 1}})
          [:body])

  (get-in (e/mb-get conn [:user]
                    {:query-params {:limit 1
                                    :offset 0}})
          [:body])

  (get-in (e/mb-get conn [:user]
                    {:query-params {:limit 1
                                    :offset 1}})
          [:body]))
