(ns iface.modules.payments
  (:require [iface.utils.norms :as norms]
            [re-frame.core :as rf :refer [reg-event-fx
                                          reg-sub
                                          subscribe]]
            [toolbelt.core :as tb]))


;; ==============================================================================
;; db ===========================================================================
;; ==============================================================================


(def path ::payments)


(def default-value
  {path {}})


(reg-sub
 path
 (fn [db _]
   (path db)))


;; ==============================================================================
;; payments =====================================================================
;; ==============================================================================


;; events =======================================================================


(defn- prepare-payment-query-params [params]
  params)


(reg-event-fx
 :payments/query
 [(rf/path path)]
 (fn [{:keys [db]} [k params]]
   {:dispatch [:ui/loading k true]
    :graphql   {:query
                [[:payments {:params (prepare-payment-query-params params)}
                  [:id :method :type :autopay :amount :status :description
                   :pstart :pend :paid_on :created :subtypes
                   [:check [:id]]
                   [:source [:id :name :type :last4]]
                   [:account [:id :name]]]]]
                :on-success [:payments/fetch-success k]
                :on-failure [:graphql/failure k]}}))


(reg-event-fx
 :payments/fetch
 [(rf/path path)]
 (fn [{:keys [db]} [k account-id]]
   (let [params (tb/assoc-when {} :account (when (some? account-id) (tb/str->int account-id)))]
     {:dispatch [:ui/loading k true]
      :graphql   {:query
                  [[:payments {:params params}
                    [:id :method :type :autopay :amount :status :description
                     :pstart :pend :paid_on :created :subtypes
                     [:check [:id]]
                     [:source [:id :name :type :last4]]
                     [:account [:id :name]]]]]
                  :on-success [:payments/fetch-success k]
                  :on-failure [:graphql/failure k]}})))


(reg-event-fx
 :payments/fetch-success
 [(rf/path path)]
 (fn [{db :db} [_ k response]]
   (let [payments (get-in response [:data :payments])]
     {:db       (norms/normalize db :payments/norms payments)
      :dispatch [:ui/loading k false]})))


;; subs =========================================================================


(reg-sub
 :payments
 :<- [path]
 (fn [db _]
   (norms/denormalize db :payments/norms)))


(reg-sub
 :payments/by-account-id
 :<- [:payments]
 (fn [payments [_ account-id]]
   (filter #(= account-id (get-in % [:account :id])) payments)))


;; given a list of status
;; get all the payments with one of the status
(reg-sub
 :payments/by-statuses
 :<-[:payments]
 (fn [payments [_ statuses]]
   (filter #(some (fn [s] (= s (:status %))) statuses) payments)))


;; ==============================================================================
;; payment sources ==============================================================
;; ==============================================================================


;; events =======================================================================


(reg-event-fx
 :payment-sources/fetch
 (fn [{:keys [db]} [k account-id opts :as v]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query
               [[:payment_sources {:account account-id}
                 [:id :last4 :customer :type :name :default :status :autopay :expires
                  [:payments [:id :method :type :autopay :amount :status :pstart :pend :paid_on :description]]
                  [:account [:id]]]]]
               :on-success [::fetch-success k account-id opts]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::fetch-success
 [(rf/path path)]
 (fn [{:keys [db]} [_ k account-id {:keys [on-success]} response]]
   (let [sources    (vec (get-in response [:data :payment_sources]))
         on-success (when-some [v on-success] (conj v sources))]
     {:dispatch-n (tb/conj-when [[:ui/loading k false]] on-success)
      :db         (norms/normalize db :payment-sources/norms sources)})))


;; subs =========================================================================


(reg-sub
 :payment-sources
 :<- [path]
 (fn [db _]
   (norms/denormalize db :payment-sources/norms)))


(reg-sub
 :payment-sources/by-type
 :<- [:payment-sources]
 (fn [sources [_ type]]
   (filter #(= (:type %) type) sources)))


(reg-sub
 :payment-sources/by-account-id
 :<- [:payment-sources]
 (fn [sources [_ account-id type]]
   (let [pred (if (some? type)
                #(and (= account-id (get-in % [:account :id]))
                      (= type (:type %)) sources)
                #(= account-id (get-in % [:account :id])))]
     (filter pred sources))))


(reg-sub
 :payment-sources/autopay-on?
 (fn [[_ account-id]]
   (subscribe [:payment-sources/by-account-id account-id]))
 (fn [sources _]
   (boolean (tb/find-by :autopay sources))))


(reg-sub
 :payment-sources/has-verified-bank?
 (fn [[_ account-id]]
   (subscribe [:payment-sources/by-account-id account-id :bank]))
 (fn [banks _]
   (not (empty? (filter #(= (:status %) "verified") banks)))))


(reg-sub
 :payment-sources/has-card?
 (fn [[_ account-id]]
   (subscribe [:payment-sources/by-account-id account-id :card]))
 (fn [cards _]
   (not (empty? cards))))
