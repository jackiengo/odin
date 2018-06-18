(ns migrations.teller.properties
  (:require [blueprints.models.property :as property]
            [datomic.api :as d]
            [teller.property :as tproperty]))


(defn- query-communities [db]
  (d/q '[:find ?p ?ops-id ?deposit-id
         :where
         [?p :property/rent-connect-id ?ops-id]
         [?p :property/deposit-connect-id ?deposit-id]]
       db))


(defn ^{:added "1.10.0"} properties-created? [teller conn]
  (let [communities (query-communities (d/db conn))]
    (= (count (for [[cid & _] communities]
                (let [community (d/entity (d/db conn) cid)]
                  (tproperty/by-community teller community))))
       (count communities))))


(defn ^{:added "1.10.0"} create-teller-properties!
  [teller conn]
  (doseq [[cid ops-id deposit-id] (query-communities (d/db conn))]
    (let [p    (d/entity (d/db conn) cid)
          fees (tproperty/construct-fees (tproperty/fee 0))]
      (tproperty/create! teller
                         (property/code p)
                         (property/name p)
                         "jesse@starcity.com"
                         {:deposit   deposit-id
                          :ops       ops-id
                          :community cid
                          :fees      fees}))))


(comment
  (do
    (def conn odin.datomic/conn)
    (def teller odin.teller/teller))

  (create-teller-properties! teller conn)

  )
