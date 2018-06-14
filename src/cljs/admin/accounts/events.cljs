(ns admin.accounts.events
  (:require [admin.accounts.db :as db]
            [admin.routes :as routes]
            [clojure.string :as string]
            [iface.modules.graphql :as graphql]
            [iface.utils.formatters :as format]
            [iface.utils.norms :as norms]
            [re-frame.core :refer [reg-event-fx
                                   reg-event-db
                                   path]]
            [toolbelt.core :as tb]))


;; ==============================================================================
;; generic ======================================================================
;; ==============================================================================


(defn- parse-query-params
  [{:keys [roles q]}]
  (tb/assoc-when
   {}
   :roles (when-some [rs roles] (vec rs))
   :q q))


(reg-event-fx
 :accounts/query
 [(path db/path)]
 (fn [{db :db} [k params]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query
               [[:accounts {:params (parse-query-params params)}
                 [:id :name :email :phone :role :created
                  [:property [:id :name]]
                  [:application [:id :move_in :created :updated :submitted :status
                                 [:communities [:id :name]]]]
                  [:active_license [:id :rate :starts :ends :term :status :rent_status
                                    [:unit [:id :code :number]]]]
                  [:deposit [:id :amount :due :amount_remaining :amount_paid :amount_pending]]]]]
               :on-success [::accounts-query k params]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::accounts-query
 [(path db/path)]
 (fn [{db :db} [_ k params response]]
   {:db       (->> (get-in response [:data :accounts])
                   (norms/normalize db :accounts/norms))
    :dispatch [:ui/loading k false]}))


(reg-event-fx
 :account/fetch
 [(path db/path)]
 (fn [{db :db} [k account-id opts]]
   (let [license-selectors [:id :rate :starts :ends :term :status :rent_status
                            [:property [:id :cover_image_url :name]]
                            [:unit [:id :code :number]]]]
     {:dispatch [:ui/loading k true]
      :graphql   {:query
                  [[:account {:id account-id}
                    [:id :name :email :phone :role :dob
                     [:deposit [:amount :due :status]]
                     [:property [:id :name]]
                     ;; TODO: Move to separate query
                     [:application [:id :move_in :created :updated :submitted :status :term :has_pet
                                    :approved_at
                                    [:approved_by [:id :name]]
                                    [:communities [:id :name]]
                                    [:fitness [:experience :skills :free_time :interested :dealbreakers :conflicts]]
                                    [:income [:id :uri :name]]
                                    [:pet [:type :breed :weight :sterile :vaccines :bitten :demeanor :daytime_care]]]]
                     [:active_license (conj license-selectors
                                            [:transition [:id :type :deposit_refund :room_walkthrough_doc :asana_task :date :early_termination_fee
                                                          [:new_license [:id :rate :term  :starts :ends
                                                                         [:unit [:name :id :number
                                                                                 [:property [:name :id]]]]]]]])]
                     ;; TODO: Move to separate query
                     [:licenses license-selectors]]]
                   [:orders {:params {:accounts [account-id]}}
                    [:id :price :created :name :status]]]
                  :on-success [::account-fetch k opts]
                  :on-failure [:graphql/failure k]}})))


(reg-event-fx
 ::account-fetch
 [(path db/path)]
 (fn [{db :db} [_ k opts response]]
   (let [account (get-in response [:data :account])
         orders  (get-in response [:data :orders])]
     {:db         (->> (assoc account :orders orders)
                       (norms/assoc-norm db :accounts/norms (:id account)))
      :dispatch-n (tb/conj-when
                   [[:ui/loading k false]]
                   (when-let [ev (:on-success opts)] (conj ev account)))})))


;; ==============================================================================
;; list view ====================================================================
;; ==============================================================================


(defmethod routes/dispatches :accounts/list [{params :params}]
  (if (empty? params)
    [[:accounts.list/set-default-route]]
    [[:accounts.list/fetch (db/parse-query-params params)]]))


(reg-event-fx
 :accounts.list/set-default-route
 [(path db/path)]
 (fn [{db :db} _]
   {:route (db/params->route (:params db))}))


(defn- accounts-query-params [{:keys [selected-view q] :as query-params}]
  (let [roles (when-not (= "all" selected-view)
                [(keyword selected-view)])]
    (tb/assoc-when {:q q} :roles roles)))


(reg-event-fx
 :accounts.list/fetch
 [(path db/path)]
 (fn [{db :db} [k query-params]]
   {:dispatch [:accounts/query (accounts-query-params query-params)]
    :db       (assoc db :params query-params)}))


(reg-event-fx
 :accounts.list/select-view
 [(path db/path)]
 (fn [{db :db} [_ view]]
   {:route (-> (:params db)
               (assoc :selected-view view)
               (merge (db/default-sort-params view))
               (db/params->route))}))


(reg-event-fx
 :accounts.list/search-accounts
 [(path db/path)]
 (fn [{db :db} [k q]]
   {:dispatch-throttle {:id              k
                        :window-duration 500
                        :leading?        false
                        :trailing?       true
                        :dispatch        [::search-accounts q]}}))


(reg-event-fx
 ::search-accounts
 [(path db/path)]
 (fn [{db :db} [_ query]]
   {:route (db/params->route (assoc (:params db) :q query))}))

;; ==============================================================================
;; entry ========================================================================
;; ==============================================================================


(defmethod routes/dispatches :accounts/entry [route]
  (let [account-id (tb/str->int (get-in route [:params :account-id]))]
    [[:account/fetch account-id {:on-success [::on-fetch-account]}]
     [:payments/fetch account-id]
     [:payment-sources/fetch account-id]
     [:notes/fetch {:refs [account-id]}]]))


(defn- tab-for-role [role]
  (case role
    :member    "membership"
    :applicant "application"
    "notes"))


(reg-event-fx
 ::on-fetch-account
 [(path db/path)]
 (fn [{db :db} [_ account]]
   (let [current (:tab db)]
     (when (or (nil? current) (not (db/allowed? (:role account) current)))
       {:dispatch [:accounts.entry/select-tab (tab-for-role (:role account))]}))))


(reg-event-db
 :accounts.entry/select-tab
 [(path db/path)]
 (fn [db [_ tab]]
   (assoc db :tab tab)))


;; fetch units ==================================================================


(reg-event-fx
 :accounts.entry.approval/fetch-units
 (fn [_ [k property-id]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query
               [[:units {:params {:property (tb/str->int property-id)}}
                 [:id :code :number
                  [:occupant [:name
                              [:active_license [:ends]]]]]]]
               :on-success [::fetch-units-success k]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::fetch-units-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   {:db       (assoc db :units (get-in response [:data :units]))
    :dispatch [:ui/loading k false]}))


;; approve ======================================================================


(reg-event-fx
 :accounts.entry/approve
 [(path db/path)]
 (fn [{db :db} [k application-id {:keys [move-in unit term community]}]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation
               [[:approve_application {:application application-id
                                       :params      {:property community
                                                     :move_in  (.toISOString move-in)
                                                     :unit     (tb/str->int unit)
                                                     :term     (tb/str->int term)}}
                 [:id [:account [:id]]]]]
               :on-success [::approve-success k]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::approve-success
 [(path db/path)]
 (fn [_ [_ k response]]
   {:dispatch-n
    [[:ui/loading k false]
     [:modal/hide :accounts.approval/modal]
     [:account/fetch (get-in response [:data :approve_application :account :id])]]}))


;; reassign =====================================================================


(defn- populate-reassign-form
  [{:keys [deposit_refund asana_task room_walkthrough_doc] :as transition}]
  (tb/assoc-when
   {:editing true}
   :deposit-refund deposit_refund
   :room-walkthrough-doc room_walkthrough_doc
   :asana-task asana_task))


(reg-event-fx
 :accounts.entry.reassign/edit
 [(path db/path)]
 (fn [{db :db} [_ transition]]
   {:db       (assoc db :reassign-form (populate-reassign-form transition))
    :dispatch [:modal/show db/reassign-modal-key]}))


(reg-event-fx
 :accounts.entry.reassign/show
 [(path db/path)]
 (fn [_ [_ account]]
   (let [current-community-id (get-in account [:property :id])]
     {:dispatch-n [[:modal/show db/reassign-modal-key]
                   [:properties/query]
                   [:accounts.entry.reassign/update :community current-community-id]
                   [:accounts.entry.reassign/update :type :intra-xfer]
                   [:property/fetch current-community-id]]})))


(reg-event-fx
 :accounts.entry.reassign/hide
 [(path db/path)]
 (fn [{db :db} _]
   {:dispatch [:modal/hide db/reassign-modal-key]}))


(reg-event-db
 :accounts.entry.reassign/clear
 [(path db/path)]
 (fn [db _] (assoc db :reassign-form {})))


(reg-event-db
 :accounts.entry.reassign/update
 [(path db/path)]
 (fn [db [_ k v]]
   (assoc-in db [:reassign-form k] v)))


(reg-event-fx
 :accounts.entry.reassign/select-community
 [(path db/path)]
 (fn [{db :db} [_ community license]]
   (let [community (tb/str->int community)
         current-community (get-in license [:property :id])]
     {:dispatch-n [[:accounts.entry.reassign/update :community community]
                   [:property/fetch community]
                   (if (= community current-community)
                     [:accounts.entry.reassign/update :type :intra-xfer]
                     [:accounts.entry.reassign/update :type :inter-xfer])]})))


(reg-event-fx
 :accounts.entry.reassign/select-unit
 [(path db/path)]
 (fn [db [_ unit term]]
   (let [unit (tb/str->int unit)]
     {:dispatch-n [[:accounts.entry.reassign/update :unit unit]
                   [:accounts.entry.reassign/fetch-rate unit term :accounts.entry.reassign/update]]})))


(reg-event-fx
 :accounts.entry.reassign/fetch-rate
 [(path db/path)]
 (fn [_ [k unit-id term on-success]]
   {:dispatch [:ui/loading k true]
    :graphql  {:query
               [[:unit {:id unit-id}
                 [[:rates [:rate :term]]
                  [:property [[:rates [:rate :term]]]]]]]
               :on-success [::fetch-rate-success k term on-success]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::fetch-rate-success
 [(path db/path)]
 (fn [_ [_ k term on-success response]]
   (let [urates (get-in response [:data :unit :rates])
         prates (get-in response [:data :unit :property :rates])
         rate   (->> [urates prates]
                     (map (comp :rate (partial tb/find-by (comp #{term} :term)))) ; oh my
                     (apply max))]
     {:dispatch-n [[:ui/loading k false]
                   [on-success :rate rate]]})))


(defn- reassign-form->transition-params
  [account {:keys [type move-out-date unit rate move-in-date asana-task] :as form-data}]
  (let [result (tb/assoc-when
                {:current_license    (get-in account [:active_license :id])
                 :type               (keyword (string/replace (name type) "-" "_"))
                 :date               (.toISOString move-out-date)
                 :new_license_params {:unit unit
                                      :rate rate
                                      :term (get-in account [:active_license :term])
                                      :date (.toISOString move-in-date)}}
                :asana_task asana-task)]
    result))

(reg-event-fx
 :accounts.entry/reassign!
 [(path db/path)]
 (fn [_ [k account form]]
   {:dispatch  [:ui/loading k true]
    :graphql {:mutation
              [[:transfer_create {:params (reassign-form->transition-params account form)}
                [:id [:account [:id]]]]]
              :on-success [::reassign-unit-success k]
              :on-failure [:graphql/failure k]}}))


(reg-event-fx
 :accounts.entry/reassign-update!
 [(path db/path)]
 (fn [{db :db} [k account form]]
   (let [transition (get-in account [:active_license :transition])
         params (tb/assoc-when
                 {:id (:id transition)
                  :current_license (get-in account [:active_license :id])}
                 :room_walkthrough_doc (:room-walkthrough-doc form)
                 :asana_task (:asana-task form)
                 :deposit_refund (:deposit-refund form))]
     {:dispatch [:ui/loading k true]
      :graphql {:mutation
                [[:transfer_update {:params params}
                  [:id [:account [:id]]]]]
                :on-success [::reassign-update-success k]
                :on-failure [:graphql/failure k]}})))


(reg-event-fx
 ::reassign-update-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   (let [account-id (get-in response [:data :transfer_update :account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:modal/hide db/reassign-modal-key]
                   [:payment-sources/fetch account-id]
                   [:notify/success ["Transfer Info updated!"]]
                   [:account/fetch account-id]]})))


(reg-event-fx
 ::reassign-unit-success
 [(path db/path)]
 (fn [_ [_ k response]]
   (let [account-id (get-in response [:data :transfer_create :account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:modal/hide db/reassign-modal-key]
                   [:payment-sources/fetch account-id]
                   [:notify/success ["Transfer created!"]]
                   [:account/fetch account-id]]})))


;; move-out transition ==========================================================


(defn- populate-transition-form
  [transition]
  (tb/assoc-when
   {:editing        true
    :written-notice true}
   :date (js/moment (:date transition))
   :asana-task (:asana_task transition)
   :room-walkthrough-doc (:room_walkthrough_doc transition)
   :deposit-refund (:deposit_refund transition)))


(reg-event-fx
 :accounts.entry.transition/populate-form
 [(path db/path)]
 (fn [{db :db} [_ transition]]
   {:db       (assoc db :transition-form (populate-transition-form transition))
    :dispatch [::show-modal]}))


(reg-event-fx
 :accounts.entry.transition/show
 [(path db/path)]
 (fn [_ [_ transition]]
   (if (some? transition)
     {:dispatch [:accounts.entry.transition/populate-form transition]}
     {:dispatch [::show-modal]})))


(reg-event-fx
 ::show-modal
 [(path db/path)]
 (fn [_ _]
   {:dispatch [:modal/show db/transition-modal-key]}))


(reg-event-fx
 :accounts.entry.transition/hide
 [(path db/path)]
 (fn [_ [_ account]]
   {:dispatch [:modal/hide db/transition-modal-key]}))


(reg-event-db
 :accounts.entry.transition/update
 [(path db/path)]
 (fn [db [_ k v]]
   (assoc-in db [:transition-form k] v)))


(reg-event-db
 :accounts.entry.transition/clear
 [(path db/path)]
 (fn [db _]
   (assoc db :transition-form {})))


(reg-event-db
 :accounts.entry.transition/add-notice
 [(path db/path)]
 (fn [db [_ date]]
   (assoc db :transition-form {:notice-date    date
                               :written-notice true})))


(reg-event-fx
 :accounts.entry/move-out!
 [(path db/path)]
 (fn [db [k license-id {:keys [date asana-task notice-date] :as form-data}]]
   {:dispatch-n [[:ui/loading k true]
                 [:accounts.entry.transition/hide]]
    :graphql    {:mutation
                 [[:move_out_create {:params {:current_license license-id
                                              :type            :move_out
                                              :date            (.toISOString date)
                                              :asana_task      asana-task
                                              :notice_date     (.toISOString notice-date)}}
                   [:id [:account [:id]]]]]
                 :on-success [::move-out-success k]
                 :on-failure [:graphql/failure k]}}))

(reg-event-fx
 :accounts.entry.renewal/populate
 [(path db/path)]
 (fn [{db :db} [k {:keys [unit rate term] :as license}]]
   {:db (assoc db :transition-form {:unit (:id unit)
                                    :rate rate
                                    :term term})}))


(reg-event-fx
 :accounts.entry.renewal/show
 [(path db/path)]
 (fn [{db :db} [_ license]]
   {:dispatch-n [[:modal/show db/renewal-modal-key]
                 [:accounts.entry.renewal/populate license]]}))


(reg-event-fx
 :accounts.entry.renewal/hide
 (fn [_ _]
   {:dispatch [:modal/hide db/renewal-modal-key]}))


(reg-event-fx
 :accounts.entry/renew-license!
 [(path db/path)]
 (fn [{db :db} [k {:keys [id ends] :as license} {:keys [unit term rate] :as form-data}]]
   (let [date (.toISOString (.add (js/moment ends) 1 "days"))]
     {:graphql {:mutation
                [[:renewal_create {:params {:current_license    id
                                            :date               date
                                            :type               :renewal
                                            :new_license_params {:unit unit
                                                                 :term term
                                                                 :rate rate
                                                                 :date date}}}
                  [:id [:account [:id]]]]]
                :on-success [::renewal-success k]
                :on-failure [:graphql/failure k]}})))


(reg-event-fx
 ::renewal-success
 [(path db/path)]
 (fn [{db :db} [_ k response]]
   (let [account-id (get-in response [:data :renewal_create :account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:notify/success ["Great! License renewed!"]]
                   [:account/fetch account-id]
                   [:accounts.entry.renewal/hide]]})))


(reg-event-fx
 ::move-out-success
 [(path db/path)]
 (fn [db [_ k response]]
   (let [account-id (get-in response [:data :move_out_create :account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:notify/success ["Move-out data created!"]]
                   [:account/fetch account-id]]})))


(reg-event-fx
 :accounts.entry/update-move-out!
 [(path db/path)]
 (fn [db [k license-id transition-id {:keys [date deposit-refund room-walkthrough-doc asana-task] :as form-data }]]
   {:dispatch-n [[:ui/loading k true]
                 [:accounts.entry.transition/hide]]
    :graphql    {:mutation
                 [[:move_out_update {:params {:id                   transition-id
                                              :current_license      license-id
                                              :date                 (.toISOString date)
                                              :deposit_refund       deposit-refund
                                              :room_walkthrough_doc room-walkthrough-doc
                                              :asana_task           asana-task}}
                   [:id [:account [:id]]]]]
                 :on-success [::move-out-update-success k]
                 :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::move-out-update-success
 (fn [db [_ k response]]
   (let [account-id (get-in response [:data :move_out_update :account :id])]
     {:dispatch-n [[:ui/loading k false]
                   [:notify/success "Move-out data updated!"]
                   [:account/fetch account-id]]})))


(reg-event-fx
 :accounts.entry.reassign/update-term
 [(path db/path)]
 (fn [db [k license term]]
   {:dispatch-n [[:accounts.entry.transition/update :term term]
                 [:accounts.entry.reassign/fetch-rate (get-in license [:unit :id]) term :accounts.entry.transition/update]]}))





;; payment ======================================================================


(reg-event-fx
 :accounts.entry/add-payment!
 [(path db/path)]
 (fn [_ [_ k account-id {:keys [type month amount] :as params}]]
   {:dispatch [:ui/loading k :true]
    :graphql  {:mutation
               [[:create_payment {:params {:account account-id
                                           :type    (keyword type)
                                           :month   month
                                           :amount  (float amount)}}
                 [:id]]]
               :on-success [::add-payment-success k params]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::add-payment-success
 [(path db/path)]
 (fn [_ [_ k params response]]
   (let [payment-id (get-in response [:data :create_payment :id])]
     {:dispatch [:accounts.entry/add-check! k (assoc params :payment payment-id)]})))


;; check ========================================================================


(reg-event-fx
 :accounts.entry/add-check!
 [(path db/path)]
 (fn [_ [_ k {:keys [payment amount name check-date received-date]}]]
   {:dispatch [:ui/loading k true]
    :graphql  {:mutation
               [[:create_check {:params {:payment       payment
                                         :amount        (float amount)
                                         :name          name
                                         :received_date received-date
                                         :check_date    check-date}}
                 [[:payment [[:account [:id]]]]]]]
               :on-success [::add-check-success k]
               :on-failure [:graphql/failure k]}}))


(reg-event-fx
 ::add-check-success
 [(path db/path)]
 (fn [_ [_ k response]]
   (let [account-id (get-in response [:data :create_check :payment :account :id])]
     {:dispatch-n
      [[:ui/loading k false]
       [:modal/hide k]
       [:payments/fetch account-id]]})))
