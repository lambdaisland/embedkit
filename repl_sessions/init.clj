(ns init
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.embedkit.setup :as setup]))

(def config {:user "admin@example.com"
             :password "secret1"})

;; create admin user and enable embedded
(setup/init-metabase! config)
;; get the embedding secret key
(def config* (assoc config
                    :secret-key (setup/get-embedding-secret-key (e/connect config))))

(def conn (e/connect config*))
