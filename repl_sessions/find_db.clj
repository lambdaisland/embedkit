(ns find-db
  (:require [lambdaisland.embedkit :as e]))

(def conn (e/connect {:user "admin@example.com"
                      :password "aqd4ijj4"
                      :secret-key "6fa6b6600d27ff276d3d0e961b661fb3b082f8b60781e07d11b8325a6e1025c5"}))

(e/find-database conn "example_tenant")

(:id (e/find-database conn "example_tenant"))

(e/mb-get conn [:database])
