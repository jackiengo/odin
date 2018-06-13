(ns member.profile.membership.subs
  (:require [member.profile.membership.db :as db]
            [re-frame.core :refer [reg-sub subscribe]]
            [toolbelt.core :as tb]))

(reg-sub
 ::membership
 (fn [db _]
   (db/path db)))


(reg-sub
 :member/license
 :<- [::membership]
 (fn [db _]
   (:license db)))


(reg-sub
 :member/deposit
 :<- [::membership]
 (fn [db _]
   (:deposit db)))


(reg-sub
 :member.license/loading?
 :<- [:member/license]
 :<- [:ui/loading? :member.license/fetch]
 (fn [[license loading]]
   (and (empty? license) loading)))


(reg-sub
 :member/rent-payments
 :<- [::membership]
 (fn [db [_ {:keys [status]}]]
   (let [payments (->> (get-in db [:license :payments])
                       (sort-by :pstart))]
     (if (some? status)
       (filter #(= status (:status %)) payments)
       payments))))


(reg-sub
 :member.rent/unpaid?
 :<- [:member/rent-payments {:status :due}]
 (fn [payments _]
   (not (empty? payments))))


(reg-sub
 :member.payments/due
 :<- [:payments]
 (fn [payments _]
   (filter
    #(and (= :due (:status %)) (not= :rent (:type %)))
    payments)))


(reg-sub
 :member.deposit/payment
 :<- [:member/deposit]
 (fn [deposit _]
   {:id          (:id deposit)
    :amount      (:amount_remaining deposit)
    :due         (:due deposit)
    :description "Remainder of your security deposit."
    :type        :deposit}))
