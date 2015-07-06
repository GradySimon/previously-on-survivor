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
  (case key :common/uuid (str datomic-value)
            :episode/air-date (str datomic-value)
            datomic-value))

(defn key-transformer [key]
  (case key :season/_episodes "season"
             (name key)))

(defn to-json [datomic-data]
  (json/write-str datomic-data
                  :key-fn key-transformer
                  :value-fn value-tranformer))

(def episode-pull-spec
  [:common/uuid
   :episode/number
   :episode/air-date
   {:season/_episodes [:common/uuid :season/number]}
   :episode/events])

(def player-pull-spec
  [:common/uuid
   :player/first-name
   :player/last-name
   :player/tribe])

(def tribe-pull-spec
  [:common/uuid
   :tribe/name
   {:player/_tribe player-pull-spec}])

(def season-pull-spec
  [:common/uuid
   :season/number
   {:season/episodes episode-pull-spec}
   {:season/tribes tribe-pull-spec}])

(defn last-episode []
  (let [db (d/db conn)]
    (->>
      (q '[:find (max ?air-date) .
           :where [_ :episode/air-date ?air-date]
                  [(< ?air-date (java.util.Date.))]]
          db)
      (q '[:find ?episode .
           :in $ ?last-air-date
           :where [?episode :episode/air-date ?last-air-date]]
         db))))

(defn next-episode []
  (let [db (d/db conn)]
    (->>
      (q '[:find (min ?air-date) .
           :where [_ :episode/air-date ?air-date]
                  [(>= ?air-date (java.util.Date.))]]
          db)
      (q '[:find ?episode .
           :in $ ?next-air-date
           :where [?episode :episode/air-date ?next-air-date]]
          db))))

(defn current-season [] 
  (let [db (d/db conn)]
    (q '[:find ?season .
         :where [?season :common/designation :common.designation/current-season]]
       db)))

(defn alive-players []
  (let [db (d/db conn)]
    (q '[:find [?player ...]
         :in $ ?current-season
         :where [?player :player/tribe ?tribe]
                [?current-season :season/tribes ?tribe]
                (not-join [?player]
                  [?current-season :season/episodes ?episode]
                  [?epsiode :episode/events ?event]
                  [?event :event.tribal/voted-out ?player])]
        db (current-season))))

(defn detail
  ([entity pull-spec]
    (let [db (d/db conn)]
      (d/pull db pull-spec entity)))
  ([identity-mode entity pull-spec]
    (let [db (d/db conn)]
      (case identity-mode
            :uuid (detail [:common/uuid (java.util.UUID/fromString entity)] pull-spec)
            :eid (detail entity pull-spec)))))

(defn detail-list [entities pull-spec]
  (let [db (d/db conn)]
    (d/pull-many db pull-spec entities)))

(defresource last-episode-resource []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail (last-episode) episode-pull-spec))))

(defresource next-episode-resource []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail (next-episode) episode-pull-spec))))

(defresource current-season-resource []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail (current-season) season-pull-spec ))))

(defresource episode-resource [uuid]
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail :uuid uuid episode-pull-spec))))

(defresource season-resource [uuid]
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail :uuid uuid season-pull-spec))))

(defresource alive-players-resource []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (to-json (detail-list (alive-players) player-pull-spec))))

(defroutes app
  (ANY "/last-episode" [] (last-episode-resource))
  (ANY "/next-episode" [] (next-episode-resource))
  (ANY "/episode/:uuid" [uuid] (episode-resource uuid))
  (ANY "/current-season" [] (current-season-resource))
  (ANY "/season/:uuid" [uuid] (season-resource uuid))
  (ANY "/alive-players" [] (alive-players-resource)))

(def handler
  (-> app
      wrap-params))
