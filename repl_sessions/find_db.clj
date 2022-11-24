(ns find-db
  (:require [lambdaisland.embedkit :as e]))

(def conn
  (e/connect (read-string (slurp "dev/config.edn"))))

(e/find-database conn "example_tenant")

(:id (e/find-database conn "example_tenant"))

(e/mb-get conn [:database])
