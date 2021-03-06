(ns odin.graphql.resolvers.service
  (:require [blueprints.models.account :as account]
            [blueprints.models.service :as service]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic.api :as d]
            [odin.graphql.authorization :as authorization]
            [taoensso.timbre :as timbre]
            [blueprints.models.source :as source]
            [toolbelt.core :as tb]
            [toolbelt.datomic :as td]
            [teller.core :as teller]
            [teller.plan :as tplan]
            [clojure.set :as set]
            [re-frame.db :as db]
            [clj-time.coerce :as c]))

;; ==============================================================================
;; helpers ======================================================================
;; ==============================================================================


(defn make-billed-key
  [billed]
  (keyword "service.billed" (name billed)))


(defn make-type-key
  [svc-type]
  (keyword "service.type" (name svc-type)))


;; ==============================================================================
;; fields =======================================================================
;; ==============================================================================


(defn billed
  [_ _ service]
  (-> (service/billed service) name keyword))


(defn svc-type
  [_ _ service]
  (-> (service/type service) name keyword))


(defn updated
  [{conn :conn} _ service]
  (let [fields  (service/fields service)
        options (mapcat service/options fields)]
    (->> (concat [service] fields options)
         (map (comp c/to-long (partial td/updated-at (d/db conn))))
         (apply max)
         (c/to-date))))


(defn excluded-days
  [{conn :conn} _ field]
  (:service-field.date/excluded-days field))


;; =============================================================================
;; Queries
;; =============================================================================


(defn- query-services
  [db params]
  (->> (tb/transform-when-key-exists params {:properties (partial map (partial d/entity db))
                                             :billed     (partial map make-billed-key)
                                             :type       (partial make-type-key)})
       (service/query db)))


(defn query
  "Query services."
  [{conn :conn} {params :params} _]
  (try
    (query-services (d/db conn) params)
    (catch Throwable t
      (timbre/error t "error querying services")
      (resolve/resolve-as nil {:message  (.getMessage t)
                               :err-data (ex-data t)}))))


(defn entry
  "Get one service by id"
  [{conn :conn} {id :id} _]
  (d/entity (d/db conn) id))


;; ==============================================================================
;; mutations ====================================================================
;; ==============================================================================


(defn- parse-service-field-option
  [{:keys [value index]}]
  (service/create-option value value {:index index}))


(defn- parse-service-field
  [{:keys [index type label required excluded_days options]}]
  (service/create-field label type
                        {:index         index
                         :required      required
                         :excluded-days excluded_days
                         :options       (map parse-service-field-option options)}))


(defn- parse-mutate-params
  [params]
  (-> (tb/assoc-when
       params
       :name-internal (:name_internal params))
      (tb/transform-when-key-exists
          {:billed   make-billed-key
           :type     make-type-key
           :catalogs (partial map #(if (string? %) (keyword %) %))
           :fields   (partial map parse-service-field)})))


(defn create!
  [{:keys [conn requester teller]} {params :params} _]
  (let [{:keys [code name description billed]} params
        params'                                (parse-mutate-params params)
        service-tx                             (service/create code name description params')]
    (if (= billed :monthly)
      (let [plan (tplan/create! teller name :payment.type/order (service/price service-tx))]
        @(d/transact conn
                     [(assoc service-tx :service/plan (td/id plan)) (source/create requester)]))
      @(d/transact conn [service-tx (source/create requester)]))
    (d/entity (d/db conn) [:service/code code])))


(defn delete!
  [{:keys [conn requester]} {:keys [service]} _]
  @(d/transact conn [[:db.fn/retractEntity service]
                     (source/create requester)])
  :ok)


(defn- update-field-option-tx*
  [db {:keys [id index label] :as params}]
  (let [e (d/entity db id)]
    (cond-> []
      (not= (:service-field-option/index e) index)
      (conj [:db/add id :service-field-option/index (int index)])

      (not= (:service-field-option/label e) label)
      (conj [:db/add id :service-field-option/label label]
            [:db/add id :service-field-option/value label]))))


(defn- update-field-options-tx
  [db field existing-options options-params]
  (let [[new old]        ((juxt remove filter) (comp some? :id) options-params)
        existing-ids     (set (map :db/id existing-options))
        update-ids       (set (map :id old))
        to-retract       (set/difference existing-ids update-ids)
        field-options-tx (map (fn [{:keys [label index]}]
                                {:db/id                 (:db/id field)
                                 :service-field/options (service/create-option label label {:index index})})
                              new)]
    (cond-> []
      (not (empty? to-retract))
      (concat (map #(vector :db.fn/retractEntity %) to-retract))

      (not (empty? old))
      (concat (remove empty? (mapcat (partial update-field-option-tx* db) old)))

      (not (empty? new))
      (concat field-options-tx))))


(defn- update-date-excluded-days-tx
  [db field existing-days days-params]
  (let [existing     (if (some? existing-days) (set existing-days) #{})
        updated      (set days-params)
        [keep added] (map set ((juxt filter remove) (partial contains? existing) updated))
        removed      (clojure.set/difference existing (clojure.set/union keep added))]

    (cond-> []
      (not (empty? added))
      (concat (map #(vector :db/add (td/id field) :service-field.date/excluded-days %) added))

      (not (empty? removed))
      (concat (map #(vector :db/retract (td/id field) :service-field.date/excluded-days %) removed)))))


(defn- update-field-tx
  [db {:keys [id label index required excluded_days options]}]
  (let [e (d/entity db id)]
    (cond-> []
      (not= label (:service-field/label e))
      (conj [:db/add id :service-field/label label])

      (not= index (:service-field/index e))
      (conj [:db/add id :service-field/index (int index)])

      (not= required (:service-field/required e))
      (conj [:db/add id :service-field/required required])

      (not= excluded_days (:service-field.date/excluded-days e))
      (concat (update-date-excluded-days-tx db e (:service-field.date/excluded-days e) excluded_days))

      (and (some? options) (not (empty? options)))
      (concat (update-field-options-tx db e (:service-field/options e) options)))))


(defn update-service-fields-tx
  [db service fields-params]
  (let [fields       (service/fields service)
        [new old]    ((juxt remove filter) (comp some? :id) fields-params)
        existing-ids (set (map :db/id fields))
        update-ids   (set (map :id old))
        to-retract   (set/difference existing-ids update-ids)]
    (cond-> []
      (not (empty? to-retract))
      (concat (map #(vector :db.fn/retractEntity %) to-retract))

      (not (empty? old))
      (concat (remove empty? (mapcat (partial update-field-tx db) old)))

      (not (empty? new))
      (concat (map (fn [{:keys [label type] :as field}]
                     (let [field (update field :options
                                         (fn [options]
                                           (when (some? options)
                                             (map #(service/create-option (:label %) (:label %) %) options))))]
                       {:db/id          (:db/id service)
                        :service/fields (service/create-field label type field)}))
                   new)))))


(defn update-service-catalogs-tx
  [service catalogs-params]
  (let [catalogs (service/catalogs service)
        keep     (filter #(% catalogs) catalogs-params)
        added    (remove #(% catalogs) catalogs-params)
        removed  (set/difference (set catalogs) (set (concat keep added)))]
    (cond-> []
      (not (empty? added))
      (concat (map #(vector :db/add (td/id service) :service/catalogs %) added))

      (not (empty? removed))
      (concat (map #(vector :db/retract (td/id service) :service/catalogs %) removed)))))


(defn update-service-properties-tx
  [service properties-params]
  (let [existing     (set (map td/id (service/properties service)))
        [keep added] (map set ((juxt filter remove) (partial contains? existing) properties-params))
        removed   (set/difference existing (set/union keep added))]
    (cond-> []
      (not (empty? added))
      (concat (map #(vector :db/add (td/id service) :service/properties %) added))

      (not (empty? removed))
      (concat (map #(vector :db/retract (td/id service) :service/properties %) removed)))))


(defn update-service-fees-tx
  [service fees-params]
  (let [existing     (set (map td/id (service/fees service)))
        [keep added] (map set ((juxt filter remove) (partial contains? existing) fees-params))
        removed      (set/difference existing (set/union keep added))]
    (cond-> []
      (not (empty? added))
      (concat (map #(vector :db/add (td/id service) :service/fees %) added))

      (not (empty? removed))
      (concat (map #(vector :db/retract (td/id service) :service/fees %) removed)))))



(defn edit-service-tx
  [existing updated]
  (let [id (:db/id existing)]
    (cond-> []
      (and (not= (:service/name existing) (:name updated)) (some? (:name updated)))
      (conj [:db/add id :service/name (:name updated)])

      (and (not= (:service/name-internal existing) (:name_internal updated)) (some? (:name_internal updated)))
      (conj [:db/add id :service/name-internal (:name_internal updated)])

      (and (not= (:service/desc existing) (:description updated)) (some? (:description updated)))
      (conj [:db/add id :service/desc (:description updated)])

      (and (not= (:service/code existing) (:code updated)) (some? (:code updated)))
      (conj [:db/add id :service/code (:code updated)])

      (and (not= (:service/type existing) (:type updated)) (some? (:type updated)))
      (conj [:db/add id :service/type (keyword "service.type" (name (:type updated)))])

      (and (not= (:service/price existing) (:price updated)) (some? (:price updated)))
      (conj [:db/add id :service/price (:price updated)])

      (and (not= (:service/cost existing) (:cost updated)) (some? (:cost updated)))
      (conj [:db/add id :service/cost (:cost updated)])

      (and (some? (:billed updated)) (not= (name (:service/billed existing)) (name (:billed updated))))
      (conj [:db/add id :service/billed (keyword "service.billed" (name (:billed updated)))])

      (and (not= (:service/rental existing) (:rental updated)) (some? (:rental updated)))
      (conj [:db/add id :service/rental (:rental updated)])

      (and (not= (:service/catalogs existing) (set (:catalogs updated))) (some? (:catalogs updated)))
      (concat (update-service-catalogs-tx existing (map keyword (:catalogs updated))))

      (and (not= (set (map td/id (:service/properties existing))) (set (map td/id (:properties updated)))))
      (concat (update-service-properties-tx existing (:properties updated)))

      (and (not= (set (map td/id (:service/fees existing))) (set (map td/id (:fees updated)))))
      (concat (update-service-fees-tx existing (:fees updated)))

      (and (not= (:service/archived existing) (:archived updated)) (some? (:archived updated)))
      (conj [:db/add id :service/archived (:archived updated)])

      (and (not= (:service/active existing) (:active updated)) (some? (:active updated)))
      (conj [:db/add id :service/active (:active updated)])

      (and (true? (:service/active existing)) (true? (:archived updated)))
      (conj [:db/add id :service/active false]))))


(defn update!
  [{:keys [conn requester]} {:keys [service_id params]} _]
  (let [service (d/entity (d/db conn) service_id)
        tx      (concat
                 (edit-service-tx service (dissoc params :fields))
                 [(source/create requester)]
                 (update-service-fields-tx (d/db conn) service (:fields params)))]
    @(d/transact conn tx)
    (d/entity (d/db conn) service_id)))


;; =============================================================================
;; Resolvers
;; =============================================================================


(defmethod authorization/authorized? :service/query
  [{conn :conn} account {params :params}]
  (or (account/admin? account)
      (let [property (account/current-property (d/db conn) account)]
        (= (:properties params) [(td/id property)]))))


(defmethod authorization/authorized? :service/create! [_ account _]
  (account/admin? account))


(defmethod authorization/authorized? :service/update! [_ account _]
  (account/admin? account))


(def resolvers
  {;; fields
   :service/billed                   billed
   :service/type                     svc-type
   :service/updated                  updated
   :service-field.date/excluded_days excluded-days
   ;; mutations
   :service/create!                  create!
   :service/update!                  update!
   ;; queries
   :service/query                    query
   :service/entry                    entry})
