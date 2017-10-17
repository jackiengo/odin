(ns odin.orders.events
  (:require [odin.orders.admin.list.events]
            [odin.orders.admin.entry.events]
            [odin.orders.db :as db]
            [re-frame.core :refer [reg-event-fx path]]
            [odin.utils.norms :as norms]
            [toolbelt.core :as tb]))



(defn- orders-query-params
  [{:keys [statuses from to datekey]}]
  (tb/assoc-when
   {:to      (.toISOString to)
    :from    (.toISOString from)
    :datekey datekey}
   :statuses (when-not (contains? statuses :all)
               (vec statuses))))


(reg-event-fx
 :orders/query
 [(path db/path)]
 (fn [{db :db} [k params]]
   {:dispatch [:loading k true]
    :graphql  {:query
               [[:orders {:params (orders-query-params params)}
                 [:id :price :created :quantity :name :desc :status :billed_on
                  [:account [:id :name]]
                  [:service [:id :name :code :billed :price]]
                  [:property [:id :name]]
                  [:payments [:id :amount]]]]]
               :on-success [::orders-query k params]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::orders-query
 [(path db/path)]
 (fn [{db :db} [_ k params response]]
   {:db       (->> (get-in response [:data :orders])
                   (norms/normalize db :orders/norms))
    :dispatch [:loading k false]}))


(reg-event-fx
 :order/fetch
 [(path db/path)]
 (fn [{db :db} [k order-id]]
   {:dispatch [:loading k true]
    :graphql  {:query
               [[:order {:id order-id}
                 [:id :price :created :quantity :name :desc :status
                  :billed_on :fulfilled_on :projected_fulfillment
                  [:meta [:attr :value :last_modified
                          [:last_modified_by [:id :name]]]]
                  [:account [:id :name [:service_source [:id]]]]
                  [:service [:id :name :code :billed :price]]
                  [:property [:id :name]]
                  [:payments [:id :amount]]]]]
               :on-success [::order-fetch k]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::order-fetch
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   (let [order (get-in response [:data :order])]
     {:db       (norms/assoc-norm db :orders/norms (:id order) order)
      :dispatch [:loading k false]})))
