(ns init
  (:require [lambdaisland.embedkit :as e]
            [lambdaisland.embedkit.repl :as r]
            [lambdaisland.embedkit.setup :as setup]))

(def config
  (select-keys (read-string (slurp "dev/config.edn"))
               [:user :password]))
(comment
  ;; first-name, last-name, site-name are optional parameters
  (def config
    (select-keys (read-string (slurp "dev/config.edn"))
                 [:user :password
                  :first-name :last-name
                  :site-name])))

;; create admin user and enable embedded


(setup/init-metabase! config)

;; setup embedding secret key
(def conn* (e/connect config))
(e/mb-put conn*
          [:setting :embedding-secret-key]
          {:form-params {:value "6fa6b6600d27ff276d3d0e961b661fb3b082f8b60781e07d11b8325a6e1025c5"}})

(comment
  ;; for debugging purpose
  ;; show all the setting kv pairs
  (setup/get-metabase-setting! conn*))

;; get the embedding secret key
(def config* (assoc config
                    :secret-key (get
                                 (setup/get-embedding-secret-key conn*)
                                 :value)))

;; begin normal connection
(def conn (e/connect config*))
