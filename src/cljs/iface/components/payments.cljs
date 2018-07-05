(ns iface.components.payments
  (:require [antizer.reagent :as ant]
            [clojure.string :as string]
            [iface.components.table :as table]
            [iface.utils.formatters :as format]
            [iface.utils.l10n :as l10n]
            [reagent.core :as r]
            [re-frame.core :as rf :refer [dispatch subscribe]]
            [tongue.core :as tongue]
            [toolbelt.core :as tb]))


;; ==============================================================================
;; l10n =========================================================================
;; ==============================================================================


(def dicts
  {:en {:view-on-stripe "View transaction on Stripe."

        :payment.status/due      "Due"
        :payment.status/canceled "Canceled"
        :payment.status/paid     "Paid"
        :payment.status/pending  "Pending"
        :payment.status/failed   "Failed"
        :payment.status/refund   "Refund"

        :payment.method/stripe-charge  "Stripe Charge"
        :payment.method/stripe-invoice "Stripe Invoice"
        :payment.method/check          "Check"

        :payment.type/rent     "Rent Payment"
        :payment.type/deposit  "Security Deposit"
        :payment.type/order    "Order"
        :payment.type/fee      "Fee"
        :payment.type/late-fee "Late Fee"
        :payment.type/other    "Uncategorized"

        :payments            "Payments"
        :sources             "Payment Methods"
        :history             "History"
        :payment-sources     "Payment Methods"
        :payment-history     "Payment History"
        :payment-history-for "Payment History for {1}"
        :transaction-history "Transaction History"
        :btn-unlink-account  "Unlink this account"
        :btn-add-new-account "Add Payment Method"

        :payment-table-no-payments "No payments to show."
        :amount                    "Amount"
        :date                      "Date"
        :paid-on                   "Paid On"
        :description               "Description"

        :autopay                "Autopay"
        :use-for-autopay        "Use this account for Autopay"
        :confirm-unlink-autopay "Are you sure you want to disable Autopay?"

        :rent-overdue-notification-body     "Your rent of {1} was due on August 1st. Want to "
        :rent-overdue-notification-body-cta "pay that now?"}

   :tongue/fallback :en})


(def lookup
  (tongue/build-translate dicts))


(defn translate [& args]
  (apply lookup l10n/*language* args))


;; ==============================================================================
;; views ========================================================================
;; ==============================================================================


(defn payment-source-icon
  "Renders an icon to illustrate a given payment source (such as :bank, :visa,
  or :amex). Defaults to :bank."
  ([source-type]
   [payment-source-icon source-type ""])
  ([source-type icon-size]
   [:span.icon {:class icon-size}
    (case source-type
      :card [:i.fa.fa-credit-card {:class icon-size}]
      [:i.fa.fa-university {:class icon-size}])]))


(defn source-name
  ([source]
   (when-some [{:keys [name last4]} source]
     (source-name name last4)))
  ([name last4]
   (str name " **** " last4)))


(defn payment-status
  "Displays payment status (paid / pending / etc)."
  [status]
  (letfn [(-hollow [txt]
            [:span.tag.is-hollow txt])]
    (case status
      "due"      (-hollow "Due")
      "canceled" (-hollow "Canceled")
      "pending"  (-hollow "Pending")
      "failed"   (-hollow "Failed")
      "paid"     (-hollow "Paid")
      "refund"   (-hollow "Refund")
      [:span])))


(defn payment-for
  "Displays payment status in tag format."
  [status]
  (letfn [(-tag [icon txt]
            [:span.tag
             [:span.icon.extra-small [:i {:class (str "fa " icon)}]]
             txt])]
    (case status
      "rent"     (-tag "fa-home" "Rent Payment")
      "deposit"  (-tag "fa-shield" "Security Deposit")
      "order"    (-tag "fa-smile-o" "Service Order")
      "late_fee" (-tag "fa-exclamation-circle" "Late Fee")
      "fee"      (-tag "fa-exclamation-circle" "Fee")
      [:span])))


(defn payment-for-icon
  "Renders an icon to illustrate a given payment type (such as :rent, :deposit, :order, or :other). Defaults to :other"
  ([reason]
   [payment-for-icon reason ""])
  ([reason icon-size]
   (when (some? reason)
     [ant/tooltip {:title     (translate (keyword "payment.type" (string/replace reason #"_" "-")))
                   :placement "right"}
      [:span.icon.has-tooltip {:class icon-size}
       (case reason
         "rent"     [:i.fa.fa-home                 {:class icon-size}]
         "deposit"  [:i.fa.fa-shield               {:class icon-size}]
         "order"    [:i.fa.fa-smile-o              {:class icon-size}]
         "late_fee" [:i.fa.fa-exclamation-circle   {:class icon-size}]
         "fee"      [:i.fa.fa-exclamation-circle   {:class icon-size}])]])))


(defn render-payment-date
  "Date appears red if it was overdue."
  [paid_on due]
  [:span (format/date-short paid_on)])


(defn render-paid-on
  [{:keys [paid_on] :as payment}]
  (let [due (when-some [d (:due payment)] (js/moment. d))]
    (cond
      (some? paid_on)
      [:span (format/date-short paid_on)]

      (and (some? due) (.isAfter (js/moment. paid_on) due))
      [ant/tooltip {:title "This payment was received late."}
       [:span.text-red (format/date-short paid_on)]]

      :otherwise
      [:div.has-text-centered {:dangerouslySetInnerHTML {:__html "&mdash;"}}])))


(defn payment-table-columns [columns]
  (->> [;; payment type icon
        {:dataIndex :type
         :className "is-narrow width-2"
         :render    (table/wrap-cljs (fn [type] [payment-for-icon type]))}

        ;; date paid
        {:title     (translate :paid-on)
         :dataIndex :paid_on
         :className "width-6"
         :render    (table/wrap-cljs
                     (fn [_ payment]
                       [render-paid-on payment]))}

        ;; amount
        {:title     (translate :amount)
         :dataIndex :amount
         :className "td-bold width-4 text-larger"
         :render    (table/wrap-cljs (fn [amount] (format/currency amount)))}

        ;; status of payment
        {:dataIndex :status
         ;;:className "is-narrow width-5"
         :render    (table/wrap-cljs (fn [status] [payment-status status]))}

        ;; description
        {:title     (translate :description)
         :dataIndex :description
         :className "expand"
         :render    (fn [val] (r/as-element [:span.yes-wrap.restrict-width val]))}

        ;; method
        {:title "Method"
         :dataIndex :method
         :render (table/wrap-cljs
                  (fn [method payment]
                    (when-some [m method]
                      [:span.tag.is-hollow m])))}

        ;; payment source
        {:title     ""
         :dataIndex :source
         :className "align-right light"
         :render    (fn [val item _] (source-name (js->clj val :keywordize-keys true)))}]
       (filter (comp columns :dataIndex))))


(defn get-payment-row-class
  "Returns a String class name for highlighting pending and due payments."
  [payment]
  (case (aget payment "status")
    "due"     "warning"
    "pending" "info"
    "default"))


(def default-columns
  #{:type :paid_on :amount :status :description :source})


(defn payments-table
  "Receives a vector of transactions, and displays them as a list."
  [payments loading? & {:keys [columns] :or {columns default-columns}}]
  (let [;; payments (if (every? (comp some? :created) payments)
        ;;            (reverse (sort-by :created payments))
        ;;            payments)
        payments (reverse (sort-by (fn [{:keys [paid_on created]}]
                                     (or paid_on created))
                                   payments))]
    [ant/table
     {:class        "payments-table"
      :loading      (or loading? false)
      :columns      (payment-table-columns columns)
      :rowClassName get-payment-row-class
      :dataSource   payments
      :locale       {:emptyText (translate :payment-table-no-payments)}}]))


(defn menu-select-source [sources]
  [:div.select
   [:select
    (for [source sources]
      (let [id (get source :id)]
        ^{:key id}
        [:option {:value id} (source-name source)]))]])


(defn- icon-class [payment-type]
  (case payment-type
    :deposit  "fa-shield"
    :rent     "fa-home"
    :late-fee "fa-exclamation-circle"
    :order    "fa-smile-o"
    ""))


(defn payment-box
  [{:keys [type amount due late_fee_due] :as payment} description]
  [:div.box
   [:div.columns
    [:div.column.is-narrow
     [:span.icon.is-large [:i {:class (str "fa fa-3x " (icon-class type))}]]]
    [:div.column.is-two-thirds
     [:h3 (translate (keyword "payment.type" (string/replace (name (:type payment)) #"_" "-")))]
     (when (some? description)
       [:p.fs2 description])]

    [:div.column.align-right
     [:h3 (format/currency (+ amount late_fee_due))]
     (if (#{:rent :deposit} type)
       [:p (str "Due by " (format/date-short due))])]]])


(defn- make-payment-modal-footer
  [payment-id {:keys [on-confirm on-cancel loading sources]
               :or   {on-confirm #(dispatch [:modal/hide payment-id])
                      loading    false}}]
  (let [selected-source (r/atom (-> sources first :id))]
    (fn [payment-id {:keys [on-confirm on-cancel loading sources]
                    :or   {on-confirm #(dispatch [:modal/hide payment-id])
                           loading    false}}]
      [:div.columns
       [:div.column.is-half
        (when (some? sources)
          [ant/select
           {:style {:width "100%" :margin-right "1rem"}
            :size  :large
            :value @selected-source
            :on-change (partial reset! selected-source)}
           (for [{id :id :as source} sources]
             ^{:key id} [ant/select-option {:value id}
                         (source-name source)])])]
       [:div.column
        [ant/button
         {:size     :large
          :on-click on-cancel}
         "Cancel"]
        [ant/button
         {:type     :primary
          :size     :large
          :on-click (fn [e]
                      (if-some [source-id @selected-source]
                        (on-confirm payment-id source-id e)
                        (on-confirm payment-id e)))
          :loading  loading}
         "Confirm Payment"]]])))


(defn make-payment-modal
  [{:keys [id type amount due] :as payment} & {:keys [on-cancel desc]
                                               :or   {on-cancel #(dispatch [:modal/hide id])}
                                               :as   opts}]
  (let [visible (subscribe [:modal/visible? id])]
    [ant/modal {:title     "Make a payment"
                :width     "640px"
                :visible   @visible
                :on-cancel on-cancel
                :footer    (let [opts (merge {:on-cancel on-cancel} opts)]
                             (r/as-element
                              [make-payment-modal-footer (:id payment) opts]))}
     [payment-box payment desc]]))



;; add check ====================================================================


(defn- check-payment-option
  [{:keys [id amount type description]}]
  [ant/radio {:value id}
   [:span
    (format/currency amount)
    " - "
    description]])


(defn- select-payment
  ([payments form-data]
   (select-payment (:payment @form-data) payments form-data))
  ([payment-id payments form-data]
   (if (= payment-id "new")
     ;; do we need to generate a payment id for a new payment??
     {:payment "new"
      :amount 0})
   (let [payment (tb/find-by #(= payment-id (:id %)) payments)]
     {:payment payment-id
      :amount   (:amount payment)})))


(defn add-check-modal-footer
  [id {:keys [payment amount name received-date check-date] :as data} on-submit]
  (let [can-submit (and payment amount name received-date check-date)
        is-loading (subscribe [:ui/loading? id])]
    [:div
     [ant/button
      {:size     :large
       :on-click #(dispatch [:modal/hide id])}
      "Cancel"]
     [ant/button
      {:type     :primary
       :size     :large
       :disabled (not can-submit)
       :on-click #(on-submit data)
       :loading  @is-loading}
      "Add Check"]]))


(defn last-rent-date
  [payments]
  (let [rent (filter #(= :rent (:type %)) payments)
        end-dates (map :pend rent)]
    (apply max end-dates)))


(defn next-rent-date
  [payments]
  (if (empty? payments)
    (js/Date.)
    (let [last (js/moment (last-rent-date payments))]
      (.toDate (.add last 1 "months")))))


(defn add-check-modal
  [id payments & {:keys [on-submit] :or {on-submit identity}}]
  (let [visible (subscribe [:modal/visible? id])
        unpaid  (filter #(= :due (:status %)) payments)
        data    (r/atom {:payment (:id (first unpaid) "new")
                         :amount  (:amount (first unpaid))
                         :name    ""
                         :type    "rent"
                         :month   (.toISOString (next-rent-date payments))})]

    (fn [id payments & {:keys [on-submit] :or {on-submit identity}}]
      [ant/modal
       {:title     "Add a Check"
        :visible   @visible
        :on-cancel #(dispatch [:modal/hide id])
        :footer    (r/as-element [add-check-modal-footer id @data on-submit])}

       [ant/form-item {:label "Choose Payment"}
        [ant/radio-group
         {:on-change #(swap! data merge (select-payment (.. % -target -value) payments data))
          :value     (:payment @data)}
         (doall
          (map-indexed
           #(with-meta (check-payment-option %2) {:key %1})
           unpaid))
         [ant/radio {:value "new"} "New payment"]]]

       ;; Create new payment
       (when (= "new" (:payment @data))
         [ant/form-item {:label "Select Payment Kind"}
          [ant/radio-group
           {:on-change #(swap! data assoc :type (.. % -target -value))
            :value     (:type @data)}
           [ant/radio {:value "rent"} "Rent"]
           [ant/radio {:value "deposit"} "Security deposit"]]])

       ;; Select month for rent
       (when (and(= "new" (:payment @data)) (= "rent" (:type @data)))
         [ant/form-item {:label "Select Month for Rent"}
          [ant/date-picker-month-picker
           {:value         (js/moment (:month @data))
            :on-change     #(swap! data assoc :month (.toISOString %))
            :disabled-date #(< (.month %) (.getMonth (next-rent-date payments)))}]])

       [:div.columns
        [:div.column
         [ant/form-item {:label "Amount"}
          [ant/input-number
           {:value     (:amount @data)
            :on-change #(swap! data assoc :amount %)
            :style     {:width "100%"}}]]]
        [:div.column
         [ant/form-item {:label "Name on Check"}
          [ant/input
           {:value     (:name @data)
            :on-change #(swap! data assoc :name (.. % -target -value))}]]]]

       [:div.columns
        [:div.column
         [ant/form-item {:label "Date on Check"}
          [ant/date-picker
           {:value     (when-let [d (:check-date @data)] (js/moment. d))
            :on-change #(swap! data assoc :check-date (.toISOString %))}]]]

        [:div.column
         [ant/form-item {:label "Date Received"}
          [ant/date-picker
           {:value     (when-let [d (:received-date @data)] (js/moment. d))
            :on-change #(swap! data assoc :received-date (.toISOString %))}]]]]])))
