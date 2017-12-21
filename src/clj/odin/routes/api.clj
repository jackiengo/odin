(ns odin.routes.api
  (:require [blueprints.models.account :as account]
            [buddy.auth.accessrules :refer [restrict]]
            [com.walmartlabs.lacinia :refer [execute]]
            [compojure.core :as compojure :refer [defroutes GET POST]]
            [odin.graphql :as graph]
            [odin.graphql.resolvers.utils :as gqlu]
            [odin.routes.kami :as kami]
            [odin.routes.util :refer :all]
            [ring.util.response :as response]
            [taoensso.timbre :as timbre]
            [toolbelt.core :as tb]
            [datomic.api :as d]
            [customs.access :as access]))

;; =============================================================================
;; GraphQL
;; =============================================================================


(defn extract-graphql-expression [request]
  (case (:request-method request)
    :get  [:query (get-in request [:params :query] "")]
    :post [:mutation (get-in request [:params :mutation] "")]))


(defn context [req]
  (gqlu/context
    (->conn req)
    (->requester req)
    (->stripe req)
    (->config req)))


(defn result->status [{:keys [errors] :as result}]
  (cond
    (nil? errors)                                      200
    (tb/find-by #(= :unauthorized (:reason %)) errors) 403
    :otherwise                                         400))


(defn graphql-handler
  [schema]
  (fn [req]
    (let [[op expr] (extract-graphql-expression req)
          result    (execute schema
                             (format "%s %s" (name op) expr)
                             nil
                             (context req))]
      (-> (response/response result)
          (response/content-type "application/transit+json")
          (response/status (result->status result))))))


;; =============================================================================
;; Config
;; =============================================================================


(def ^:private admin-config
  {:role :admin
   :features
   {:home        {}
    :profile     {}
    :accounts    {}
    :metrics     {}
    :communities {}
    :kami        {}
    :orders      {}
    :services    {}}})


(def ^:private member-config
  {:role :member
   :features
   {:home    {}
    :profile {}}})


(defn make-config [req]
  (let [account (->requester req)]
    (case (account/role account)
      :account.role/admin admin-config
      :account.role/member member-config)))


(defn inject-account [config account]
  (assoc config :account {:id    (:db/id account)
                          :name  (format "%s %s"
                                         (account/first-name account)
                                         (account/last-name account))
                          :email (account/email account)
                          :role  (account/role account)}))


(defn config-handler [req]
  (let [account (->requester req)
        config  (-> (make-config req) (inject-account account))]
    (-> (response/response config)
        (response/content-type "application/transit+json")
        (response/status 200))))


;; =============================================================================
;; History
;; =============================================================================


(defn- query-history
  [db e]
  (d/q '[:find ?attr ?type ?v ?tx-time ?account
         :in $ ?e
         :where
         [?e ?a ?v ?t true]
         [?a :db/ident ?attr]
         [?a :db/valueType ?_type]
         [?_type :db/ident ?type]
         [?t :db/txInstant ?tx-time]
         [(get-else $ ?t :source/account false) ?account]]
       (d/history db) e))


(defn- resolve-value
  [db type value]
  (if (not= type :db.type/ref)
    value
    (let [e (d/entity db value)]
      (or (:db/ident e) value))))


(defn history
  "Produce a list of all changes to entity `e`, the instant at time in which the
  change occurred, and the user that made the change (if present)."
  [db e]
  (->> (query-history db e)
       (mapv
        (fn [[attr type value tx-time account]]
          (let [value   (resolve-value db type value)
                account (when-let [account (d/entity db account)]
                          {:id   (:db/id account)
                           :name (account/short-name account)})]
            (tb/assoc-when
             {:a attr
              :v value
              :t tx-time}
             :account account))))))


;; =============================================================================
;; Routes
;; =============================================================================


(defroutes routes
  (GET "/config" [] config-handler)


  (GET "/history/:entity-id" [entity-id]
       (fn [req]
         (let [db (d/db (->conn req))]
           (-> (response/response {:data {:history (history db (tb/str->int entity-id))}})
               (response/content-type "application/transit+json")
               (response/status 200)))))


  (GET "/graphql" [] (graphql-handler graph/schema))
  (POST "/graphql" [] (graphql-handler graph/schema))

  (GET "/income/:file-id" [file-id]
       (-> (fn [req]
             (let [file (d/entity (d/db (->conn req)) (tb/str->int file-id))]
               (response/file-response (:income-file/path file))))
           (restrict {:handler {:and [access/authenticated-user
                                      (access/user-isa :account.role/admin)]}})))

  (compojure/context "/kami" [] kami/routes))
