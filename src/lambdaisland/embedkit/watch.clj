(ns lambdaisland.embedkit.watch
  "Watch Metabase entities, development aid.

  This is meant to help you figure out how operations in the UI correspond with
  data in the API. You [[watch!]] a given path, it will be polled, and every
  time the response differs from the previous response, you will see any changed
  or new values in the response."
  (:require [lambdaisland.embedkit :as e]
            [clojure.pprint :as pprint]))

(defn- base-type [obj]
  (cond
    (map? obj) :map
    (sequential? obj) :seq
    (number? obj) :number
    (string? obj) :string
    :else (class obj)))

(defn only-changes [before after]
  (if (= (base-type before) (base-type after))
    (cond
      (map? after)
      (into {}
            (comp (remove (fn [[k v]]
                            (= v (get before k))))
                  (map (fn [[k v]]
                         [k (only-changes (get before k) v)])))
            after)
      (sequential? after)
      (into []
            (map (fn [[v1 v2]] (only-changes v1 v2)))
            (loop [res []
                   [b & bs] before
                   [a & as] after]
              (if (or (seq bs) (seq as))
                (recur (conj res [b a]) bs as)
                (conj res [b a]))))
      :else after)
    after))

(defonce watches (atom {}))

(defn watch!
  "Watch a given resource, this keep re-fetching it from the API, and printing
  any keys that have changed. This allows us to make changes in the UI and see
  how they correspond with the data."
  [conn path]
  (let [obj (:body (e/mb-get conn path))]
    (swap! watches assoc path obj)
    (future
      (loop []
        (let [before (get @watches path)]
          (when before
            (let [after (:body (e/mb-get conn path))]
              (when (not= before after)
                (println "----" path "----")
                (pprint/pprint (only-changes before after))
                (swap! watches path after)
                (Thread/sleep 2500))
              (recur))))))))

(defn unwatch! [id]
  (swap! watches dissoc id))

(comment
  (watch my-conn [:card 12])
  (watch my-conn [:dashboard 100]))
