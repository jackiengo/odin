(ns odin.graphql.resolvers.member-license
  (:require [blueprints.models.account :as account]
            [blueprints.models.license-transition :as license-transition]
            [blueprints.models.member-license :as member-license]
            [blueprints.models.source :as source]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [datomic.api :as d]
            [odin.graphql.authorization :as authorization]
            [odin.graphql.resolvers.utils.autopay :as autopay-utils]
            [odin.graphql.resolvers.utils.plans :as plans-utils]
            [taoensso.timbre :as timbre]
            [teller.customer :as tcustomer]
            [teller.payment :as tpayment]
            [teller.plan :as tplan]
            [teller.subscription :as tsubscription]
            [toolbelt.date :as date]
            [toolbelt.core :as tb]
            [toolbelt.datomic :as td]))

;; ==============================================================================
;; helpers ======================================================================
;; ==============================================================================


(defn- license-customer
  "Given a member's `license`, produce the teller customer."
  [teller license]
  (tcustomer/by-account teller (member-license/account license)))


(defn- autopay-on?
  [teller license]
  (let [customer (license-customer teller license)]
    (-> (tsubscription/query teller {:customers [customer]
                                     :payment-types   [:payment.type/rent]})
        seq
        boolean)))


;; ==============================================================================
;; fields -----------------------------------------------------------------------
;; ==============================================================================


(defn autopay-on
  "Whether or not autopay is active for this license."
  [{teller :teller} _ license]
  (autopay-on? teller license))


(defn- payment-within
  [teller license date]
  (let [customer (license-customer teller license)
        tz       (member-license/time-zone license)
        from     (date/beginning-of-month date tz)
        to       (date/end-of-month date tz)]
    (when (some? customer)
      (first
       (tpayment/query teller {:customers     [customer]
                               :payment-types [:payment.type/rent]
                               :statuses      [:payment.status/due]
                               :from          from
                               :to            to})))))


(defn rent-status
  "What's the status of this license owner's rent?"
  [{teller :teller} _ license]
  (when-some [payment (payment-within teller license (java.util.Date.))]
    (cond
      (tpayment/due? payment)     :due
      (tpayment/pending? payment) :pending
      (tpayment/paid? payment)    :paid
      (tpayment/overdue? payment) :overdue
      :otherwise                  :due)))


(defn status
  "The status of the member license."
  [_ _ license]
  (keyword (name (member-license/status license))))


(defn- rent-payments
  "All rent payments made by the owner of this license."
  [{teller :teller} _ license]
  (tpayment/query teller {:customers     [(license-customer teller license)]
                          :payment-types [:payment.type/rent]}))


(defn license-transition-type
  [{:keys [conn] :as ctx} _ transition]
  (-> (license-transition/type transition)
      (name)
      (clojure.string/replace "-" "_")
      (keyword)))


(defn transition
  "Retrieves license transition information for current license. If no transition, resolves as an empty map"
  [{:keys [conn] :as ctx} _ license]
  (license-transition/by-license-id (d/db conn) (td/id license)))


;; ==============================================================================
;; mutations --------------------------------------------------------------------
;; ==============================================================================


(defn- reassign-autopay!
  [{:keys [conn teller requester]} {:keys [license unit rate]}]
  (try
    (let [license-after (d/entity (d/db conn) license)
          account       (member-license/account license-after)
          customer      (tcustomer/by-account teller account)
          old-sub       (->> (tsubscription/query teller {:customers     [customer]
                                                          :payment-types [:payment.type/rent]})
                             (tb/find-by tsubscription/active?))
          old-plan      (tsubscription/plan old-sub)
          source        (tsubscription/source old-sub)
          new-plan      (tplan/create! teller (plans-utils/plan-name teller license-after) :payment.type/rent rate)]
      (tsubscription/cancel! old-sub)
      (tplan/deactivate! old-plan)
      (tsubscription/subscribe! customer new-plan {:source   source
                                                   :start-on (autopay-utils/autopay-start customer)})
      (d/entity (d/db conn) license))
    (catch Throwable t
      (timbre/error t ::reassign-room {:license license :unit unit :rate rate})
      (resolve/resolve-as nil {:message "Failed to completely reassign room! Likely to do with autopay..."}))))


(defn reassign!
  "Reassign a the member with license `license` to a new `unit`."
  [{:keys [conn teller requester] :as ctx} {{:keys [license unit rate] :as params} :params} _]
  (let [license-before (d/entity (d/db conn) license)]
    (when (or (not= rate (member-license/rate license-before))
              (not= unit (member-license/unit license-before)))
      @(d/transact conn [{:db/id               license
                          :member-license/rate rate
                          :member-license/unit unit}
                         (source/create requester)])
      (if (autopay-on? teller license-before)
        (reassign-autopay! ctx params)
        (d/entity (d/db conn) license)))))

(def early-termination-rate
  "The amount per day that is charged as part of the Early Termination Fee"
  10)


(defn- calculate-early-termination-fee
  "Calclates an Early Termination Fee for members moving out before the end of their license term."
  [move-out term-end]
  (-> (t/interval move-out term-end)
      (t/in-days)
      (inc) ;; for some reason `interval` is off-by-one
      (* early-termination-rate)))

(defn- generate-transition-fees
  "Generates fees that are incurred with certain kinds of license transitions"
  [license transition-type move-out-date]

  (let [term-end-date (c/from-date (member-license/ends license))
        move-out-date (c/from-date move-out-date)]
   (if (and (= transition-type :move-out)
            (t/before? move-out-date term-end-date))
     (str "this chump is gonna pay an ETF of "
          (calculate-early-termination-fee move-out-date term-end-date))
     "\n\n nope no fees here")))


(defn create-license-transition!
  "Creates a license transition for a member's license"
  [{:keys [conn requester] :as ctx} {{:keys [current_license type date deposit_refund]} :params} _]
  (let [type (keyword (clojure.string/replace (name type) "_" "-"))
        license (d/entity (d/db conn) current_license)
        transition (license-transition/create current_license type date deposit_refund)
        fees (generate-transition-fees license type date)]
    (timbre/info "\n\n =======feeeeeeeeeeeeeeeeeeeeeeeeeeeeeeees ================== " fees)
    @(d/transact conn [transition
                       (source/create requester)])
    (d/entity (d/db conn) current_license)))


;; ==============================================================================
;; resolvers --------------------------------------------------------------------
;; ==============================================================================


(defmethod authorization/authorized? :member-license/reassign! [_ account _]
  (account/admin? account))


(def resolvers
  {;; fields
   :member-license/status        status
   :member-license/autopay-on    autopay-on
   :member-license/rent-payments rent-payments
   :member-license/rent-status   rent-status
   :member-license/transition    transition
   :license-transition/type      license-transition-type
   ;; mutations
   :member-license/reassign!     reassign!
   :license-transition/create!   create-license-transition!})


(comment

  @(d/transact conn [(license-transition/create (d/entity (d/db conn) 285873023223128) :pending 1500.00)])

  @(d/transact conn [(license-transition/create (d/entity (d/db conn) 285873023223148) :pending 2000.00)])

  (license-transition/by-license-id (d/db conn) 285873023223128)

  (license-transition/by-type (d/db conn) :pending)

  (d/touch (first *1))

  )
