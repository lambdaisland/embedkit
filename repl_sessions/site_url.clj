(ns site-url
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.embedkit.setup :as setup]))

(def config
  (read-string (slurp "dev/config.edn")))

(def conn (e/connect config))

(e/mb-put conn [:setting :site-url]
          {:form-params {:value "www.google.com"}})
