(ns previously-on-survivor.core
  (:gen-class)
  (:require [clojure.data.json :as json]
            [liberator.core :refer [resource defresource]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.core :refer [defroutes ANY]]
            [datomic.api :as d :refer [q]]))

(def database-uri "datomic:mem://previously-on-survivor")

(d/create-database database-uri)

(defonce conn (d/connect database-uri))

(def schema-tx
  (read-string (slurp "schema.edn")))

@(d/transact conn schema-tx)

(def initial-data-tx
  (map #(assoc % :common/uuid (d/squuid)) (read-string (slurp "season3-data.edn"))))

@(d/transact conn initial-data-tx)

(defn value-tranformer [key datomic-value]
  (cond (= key :common/uuid) (str datomic-value)
        (= key :episode/air-date) (str datomic-value)
        :else datomic-value))

(defn key-transformer [key]
  (cond (= key :season/_episodes) "season"
        :else (name key)))

(defn to-json [datomic-data]
  (json/write-str datomic-data
                  :key-fn key-transformer
                  :value-fn value-tranformer))

(def episode-pull-spec
  '[:common/uuid :episode/number :episode/air-date {:season/_episodes [:common/uuid :season/number]}])

(defn last-episode [_]
  (let [db (d/db conn)]
    (->>
      (q '[:find (max ?air-date) .
           :where [_ :episode/air-date ?air-date]
                  [(< ?air-date (java.util.Date.))]]
          db)
      (q '[:find ?episode .
           :in $ ?last-air-date
           :where [?episode :episode/air-date ?last-air-date]]
         db)
      (d/pull db episode-pull-spec)
      (to-json))))

(defn next-episode [_]
  (let [db (d/db conn)]
    (->>
      (q '[:find (min ?air-date) .
           :where [_ :episode/air-date ?air-date]
                  [(> ?air-date (java.util.Date.))]]
          db)
      (q '[:find ?episode .
           :in $ ?next-air-date
           :where [?episode :episode/air-date ?next-air-date]]
          db)
      (d/pull db episode-pull-spec)
      (to-json))))

(defn episode [episode-uuid]
  (let [db (d/db conn)]
    (->> [:common/uuid (java.util.UUID/fromString episode-uuid)]
         (d/pull db episode-pull-spec)
         (to-json))))

(defresource episode-by-uuid [uuid]
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (episode uuid)))

(defroutes app
  (ANY "/next-episode" [] (resource
                            :available-media-types ["application/json"]
                            :handle-ok next-episode))
  (ANY "/last-episode" [] (resource
                            :available-media-types ["application/json"]
                            :handle-ok last-episode))
  (ANY "/episode/:uuid" [uuid] (episode-by-uuid uuid)))

(def handler
  (-> app
      wrap-params))
