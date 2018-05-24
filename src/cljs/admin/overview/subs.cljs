(ns admin.overview.subs
  (:require [admin.overview.db]
            [re-frame.core :refer [reg-sub]]
            [iface.utils.time :as time]))


;; TODO make sure sort by works correctly, right now everyone has the same term end
(reg-sub
 :overview.accounts/end-of-term
 :<- [:accounts/list]
 (fn [accounts _]
   (->> (remove #(some? (get-in % [:active_license :move_out :date])) accounts)
        (remove #(< 45 (time/days-between (get-in % [:active_license :ends]))))
        #_(remove #(> 30 (time/days-between (get-in % [:active_license :ends]))))
        (sort-by #(time/days-between (get-in % [:active_license :ends])) <))))


;; TODO when move-out is ready, make sure this works properly
(reg-sub
 :overview.accounts/move-out
 :<- [:accounts/list]
 (fn [accounts _]
   (remove #(nil? (:transition %)) accounts)))


(reg-sub
 :overview/loading
 :<- [:ui/loading? :services.orders/query]
 :<- [:ui/loading? :payments/fetch]
 :<- [:ui/loading? :accounts/query]
 (fn [loading _]
   (some true? loading)))
