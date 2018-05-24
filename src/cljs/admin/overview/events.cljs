(ns admin.overview.events
  (:require [admin.routes :as routes]
            [admin.services.orders.events]
            [admin.overview.db :as db]
            [re-frame.core :refer [reg-event-db
                                   reg-event-fx
                                   path]]
            [toolbelt.core :as tb]))

(defmethod routes/dispatches :overview
  [{:keys [params] :as route}]
  [[:services.orders/query {:statuses [:pending :placed]}]
   [:payments/query {:statuses [:due :failed]}]
   [:accounts/query {:roles [:member]}]])
