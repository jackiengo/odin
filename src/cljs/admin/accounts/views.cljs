(ns admin.accounts.views
  (:require [admin.accounts.db :as db]
            [admin.accounts.views.application :as application]
            [admin.accounts.views.notes :as notes]
            [admin.content :as content]
            [admin.routes :as routes]
            [antizer.reagent :as ant]
            [clojure.string :as string]
            [iface.components.membership :as membership]
            [iface.components.order :as order]
            [iface.components.table :as table]
            [iface.loading :as loading]
            [iface.components.typography :as typography]
            [iface.components.payments :as payments]
            [iface.utils.formatters :as format]
            [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [toolbelt.core :as tb]
            [iface.components.form :as form]))


;; ==============================================================================
;; list view ====================================================================
;; ==============================================================================


(defn role-menu []
  (let [selected (subscribe [:accounts.list/selected-view])]
    [ant/menu {:mode          :horizontal
               :selected-keys [@selected]
               :on-click      #(dispatch [:accounts.list/select-view (aget % "key")])}
     [ant/menu-item {:key "member"} "Members"]
     [ant/menu-item {:key "applicant"} "Applicants"]
     [ant/menu-item {:key "all"} "All"]]))


(def render-name
  (table/wrap-cljs
   (fn [_ {:keys [name id]}]
     [:a {:href (routes/path-for :accounts/entry :account-id id)} name])))


(def render-email
  (table/wrap-cljs
   (fn [email {id :id}]
     [:a {:href (routes/path-for :accounts/entry :account-id id)} email])))


(def render-property
  (table/wrap-cljs
   (fn [_ {property :property}]
     [:a
      {:href (routes/path-for :properties/entry :property-id (:id property))}
      (:name property)])))


(def render-unit
  (table/wrap-cljs
   (fn [_ {license :active_license}]
     (get-in license [:unit :code]))))


(def render-term
  (table/wrap-cljs
   (fn [_ {{:keys [term starts ends]} :active_license}]
     [ant/tooltip {:title (str (format/date-short starts) "-"
                               (format/date-short ends))}
      [:div.has-text-centered term]])))

(def render-rent-status
  (table/wrap-cljs
   (fn [_ {license :active_license}]
     [:div.has-text-right (:rent_status license "N/A")])))


(defmulti columns (fn [role _] role))


(defmethod columns :member [_ query-params]
  [{:title     "Name"
    :dataIndex :name
    :render    render-name}
   {:title     "Email"
    :dataIndex :email
    :render    render-email}
   {:title     "Phone"
    :dataIndex :phone
    :render    (table/maybe-render format/phone-number)}
   {:title     (table/sort-col-title query-params :property "Community" db/params->route)
    :dataIndex :property
    :render    render-property}
   {:title     (table/sort-col-title query-params :unit "Unit" db/params->route)
    :dataIndex :unit
    :render    render-unit}
   {:title     (table/sort-col-title query-params :license_term "Term (months)" db/params->route)
    :dataIndex :term
    :render    render-term}
   {:title     "Rent Status"
    :dataIndex :rent-status
    :render    render-rent-status}])


(def render-communities
  (table/wrap-cljs
   (fn [_ account]
     (->> (for [c (get-in account [:application :communities])]
            [:small.fs1 [:a {:href (routes/path-for :properties/entry :property-id (:id c))} (:name c)]])
          (interpose ", ")
          (into [:div.has-text-right])))))


(defmethod columns :applicant [_ query-params]
  [{:title     "Name"
    :dataIndex :name
    :render    render-name}
   {:title     "Email"
    :dataIndex :email
    :render    render-email}
   {:title     "Phone"
    :dataIndex :phone
    :render    (table/maybe-render format/phone-number)}
   {:title     (table/sort-col-title query-params :move_in "Move-in" db/params->route)
    :dataIndex [:application :move_in]
    :render    (table/maybe-render format/date-short)}
   {:title     (table/sort-col-title query-params :created "Started" db/params->route)
    :dataIndex [:application :created]
    :render    (table/maybe-render format/date-short)}
   {:title     (table/sort-col-title query-params :updated "Last Activity" db/params->route)
    :dataIndex [:application :updated]
    :render    (table/maybe-render format/date-time-short)}
   {:title     (table/sort-col-title query-params :submitted "Submitted" db/params->route)
    :dataIndex [:application :submitted]
    :render    (table/maybe-render format/date-time-short)}
   {:title     "Communities"
    :dataIndex [:application :communities]
    :render    render-communities}])


(def render-role
  (table/wrap-cljs
   (fn [role {:keys [active_license] :as acct}]
     ;; TODO: Quick hack
     [:div.has-text-right
      (if (and (= role "member") (nil? active_license))
        (str (name role) " (inactive)")
        (name role))])))


(defmethod columns :all [_ query-params]
  [{:title     "Name"
    :dataIndex :name
    :render    render-name}
   {:title     "Email"
    :dataIndex :email
    :render    render-email}
   {:title     "Phone"
    :dataIndex :phone
    :render    (table/maybe-render format/phone-number)}
   {:title     (table/sort-col-title query-params :created "Created" db/params->route)
    :dataIndex :created
    :render    format/date-time-short}
   {:title     "Role"
    :dataIndex :role
    :render    render-role}])


(defn accounts-search []
  (let [is-loading   (subscribe [:ui/loading? :accounts/query])
        search-query (:q @(subscribe [:accounts.list/query-params]))]
    [ant/form-item
     [ant/input-search
      {:placeholder   "Search by name or email"
       :style         {:width "100%"}
       :on-change     #(dispatch [:accounts.list/search-accounts (.. % -target -value)])
       :default-value search-query
       :prefix        (when @is-loading (r/as-element [ant/icon {:type "loading"}]))}]]))


(defn accounts-table []
  (let [selected   (subscribe [:accounts.list/selected-view])
        accounts   (subscribe [:accounts/list])
        params     (subscribe [:accounts.list/query-params])
        is-loading (subscribe [:ui/loading? :accounts/query])]
    [:div
     [:div.table-controls
      [:div.columns
       [:div.column.is-one-third
        [accounts-search]]]]
     [ant/spin
      (tb/assoc-when
       {:tip      "Fetching accounts..."
        :spinning @is-loading}
       :delay (when-not (empty? @accounts) 1000))
      [ant/table {:columns    (columns (keyword @selected) @params)
                  :dataSource (map-indexed #(assoc %2 :key %1) @accounts)}]]]))


;; entrypoint ===================================================================


(defmethod content/view :accounts/list [route]
  [:div
   (typography/view-header "People" "Manage members and applicants")
   [role-menu]
   [:div.mt2
    [accounts-table]]])


;; ==============================================================================
;; entry view ===================================================================
;; ==============================================================================


(defn- most-current-license [account]
  (or (tb/find-by (comp #{:active} :status) (:licenses account))
      (first (:licenses account))))


;; subheader ====================================================================


(defmulti subheader :role)


(defmethod subheader :default [{:keys [role]}]
  [:b role])


(defmethod subheader :applicant [{:keys [application]}]
  [:span "Began his/her application on "
   [:b (format/date-short (:created application))]
   " and was last active at "
   [:b (format/date-time (:updated application))]])


(defmethod subheader :member [account]
  (let [{:keys [status property unit]} (most-current-license account)]
    [:span
     (if (= status :active) "Lives" [:i "Lived"])
     " in " [:a {:href (routes/path-for :properties/entry :property-id (:id property))} (:name property)]
     " in room #"
     [:b (:number unit)]]))


;; contact info =================================================================


(defn contact-info [{:keys [email phone dob]}]
  [:div
   [:p.has-text-right.fs1
    [:a {:href (str "mailto:" email)} email]
    [ant/icon {:class "ml1" :type "mail"}]]
   (when-some [p phone]
     [:p.has-text-right.fs1
      (format/phone-number p)
      [ant/icon {:class "ml1" :type "phone"}]])
   (when-some [d dob]
     [:p.has-text-right.fs1
      (format/date-month-day d)
      [ant/icon {:class "ml1" :type "gift"}]])])


;; status bar ===================================================================


(def status-icon-off
  {:class "text-grey" :style {:fontSize "20px"}})


(def status-icon-on
  {:class "text-blue" :style {:fontSize "20px"}})


(defn status-icon [type {:keys [style class]}]
  [:i.fa {:class (str type " " class) :type type :style style}])


(defn status-icons [& icon-specs]
  (for [[label icon-name enabled tooltip opts] icon-specs]
    ^{:key icon-name}
    [:div.level-item.has-text-centered
     [:div
      [:p.heading label]
      [ant/tooltip {:title tooltip}
       (->> (cond
              (some? opts) opts
              enabled      status-icon-on
              :otherwise   status-icon-off)
            (status-icon icon-name))]]]))


(defn- rent-tooltip [rent-status]
  (case rent-status
    :paid    "Rent is paid."
    :due     "Rent is due."
    :overdue "Rent is overdue"
    :pending "A rent payment is pending."
    ""))


(defn- rent-style [rent-status]
  (-> (case rent-status
        :paid    {:class "text-green"}
        :due     {:class "text-yellow"}
        :overdue {:class "text-red"}
        :pending {:class "text-blue"}
        {})
      (assoc :style {:fontSize "20px"})))


(defn- deposit-tooltip [deposit-status]
  (case deposit-status
    :paid    "Deposit is paid in full."
    :partial "Deposit is partially paid."
    :overdue "Deposit is overdue."
    :unpaid  "Deposit is unpaid."
    :pending "Deposit payment(s) are pending."
    ""))


(defn- deposit-style [deposit-status]
  (-> (case deposit-status
        :paid    {:class "text-green"}
        :partial {:class "text-yellow"}
        :overdue {:class "text-red"}
        :unpaid  {:class "text-grey"}
        :pending {:class "text-blue"}
        {})
      (assoc :style {:fontSize "20px"})))


(defn status-bar [account]
  (let [autopay-on     (subscribe [:payment-sources/autopay-on? (:id account)])
        has-bank       (subscribe [:payment-sources/has-verified-bank? (:id account)])
        has-card       (subscribe [:payment-sources/has-card? (:id account)])
        rent-status    (:rent_status (most-current-license account))
        deposit-status (get-in account [:deposit :status])]
    [ant/card
     [:div.level.is-mobile
      (status-icons
       ["rent" "fa-home" (= rent-status :paid) (rent-tooltip rent-status) (rent-style rent-status)]
       ["deposit" "fa-shield" (= deposit-status :paid) (deposit-tooltip deposit-status)
        (deposit-style deposit-status)]
       ["autopay" "fa-refresh" @autopay-on (if @autopay-on "Autopay is on." "Autopay is NOT on.")]
       ["bank account" "fa-university" @has-bank (if @has-bank "Bank account is linked." "No bank account linked.")]
       ["credit card" "fa-credit-card" @has-card (if @has-card "A credit/debit card is linked." "No credit/debit cards linked.")])]]))


(defn application-view [account]
  (let [{:keys [fitness has_pet pet] :as application}
        @(subscribe [:account/application (:id account)])]
    [:div.columns
     [:div.column
      [application/overview-card account application]
      [application/pet-card application]]
     [:div.column
      [application/community-fitness-card application]]]))


;; payments =====================================================================


(defn payments-table [account]
  (let [payments   (subscribe [:payments/by-account-id (:id account)])
        is-loading (subscribe [:ui/loading? :payments/fetch])]
    [ant/card {:class "is-flush"}
     [payments/payments-table @payments @is-loading
      :columns (conj payments/default-columns :add-check :method)]]))


(defn payments-view [account]
  (let [payments  (subscribe [:payments/by-account-id (:id account)])
        modal-key :accounts.entry/add-check-modal]
    [:div.columns
     [:div.column
      [:div.columns
       [:div.column
        [:p.title.is-5 "Payments"]]
       [:div.column.has-text-right
        [payments/add-check-modal modal-key @payments
         :on-submit #(if (= "new" (:payment %))
                       (dispatch [:accounts.entry/add-payment! modal-key (:id account) %])
                       (dispatch [:accounts.entry/add-check! modal-key %]))]
        [ant/button
         {:type     :dashed
          :on-click #(dispatch [:modal/show modal-key])
          :icon     "plus"}
         "Add Check"]]]
      [payments-table account]]]))


;; notes ========================================================================


(defn notes-view [account]
  (let [notes (subscribe [:accounts.entry/notes])]
    [:div.columns
     [:div.column
      [:div.mb2 [notes/new-note-form account]]
      (doall
       (map-indexed
        #(with-meta [notes/note-card %2] {:key %1})
        @notes))
      (when-not (empty? @notes)
        [notes/pagination])]]))


;; membership ===================================================================


(defn- reassign-unit-option
  [{:keys [id code number occupant] :as unit}]
  [ant/select-option {:value (str id)}
   (if (some? occupant)
     (format/format "Unit #%d (occupied by %s until %s)"
                    number
                    (:name occupant)
                    (-> occupant :active_license :ends format/date-short))
     (str "Unit #" number))])


(defn- reassign-modal-footer
  [account {:keys [rate unit] :as form}]
  (let [is-loading (subscribe [:ui/loading? :accounts.entry/reassign!])
        license-id (get-in account [:active_license :id])]
    [:div
     [ant/button
      {:size     :large
       :on-click #(dispatch [:modal/hide db/reassign-modal-key])}
      "Cancel"]
     [ant/button
      {:type     :primary
       :size     :large
       :disabled (or (nil? rate) (nil? unit))
       :loading  @is-loading
       :on-click #(dispatch [:accounts.entry/reassign! license-id form])}
      "Reassign"]]))


(defn- reassign-modal [account]
  (let [is-visible    (subscribe [:modal/visible? db/reassign-modal-key])
        units-loading (subscribe [:ui/loading? :property/fetch])
        rate-loading  (subscribe [:ui/loading? :accounts.entry.reassign/fetch-rate])
        units         (subscribe [:property/units (get-in account [:property :id])])
        form          (subscribe [:accounts.entry.reassign/form-data])
        license       (:active_license account)]
    [ant/modal
     {:title     (str "Reassign " (:name account))
      :visible   @is-visible
      :on-cancel #(dispatch [:modal/hide db/reassign-modal-key])
      :footer    (r/as-element [reassign-modal-footer account @form])}

     ;; unit selection
     [ant/form-item {:label "Which unit?"}
      (if @units-loading
        [:div.has-text-centered
         [ant/spin {:tip "Fetching units..."}]]
        [ant/select
         {:style     {:width "100%"}
          :value     (str (:unit @form))
          :on-change #(dispatch [:accounts.entry.reassign/select-unit % (:term license) :accounts.entry.reassign/update])}
         (doall
          (map-indexed
           #(with-meta (reassign-unit-option %2) {:key %1})
           @units))])]

     ;; rate selection
     [ant/form-item
      {:label "What should his/her rate change to?"}
      (if @rate-loading
        [:div.has-text-centered
         [ant/spin {:tip "Fetching current rate..."}]]
        [ant/input-number
         {:style     {:width "100%"}
          :value     (:rate @form)
          :disabled  (nil? (:unit @form))
          :on-change #(dispatch [:accounts.entry.reassign/update :rate %])}])]]))


(defn- move-out-confirmation [account form]
  (let [license-id (get-in account [:active_license :id])]
    (ant/modal-confirm
     {:title   "Confirm Move-Out"
      :content "Are you sure you want to continue? This action can't easily be undone and will send an email notification to the member."
      :on-ok   #(dispatch [:accounts.entry/move-out! license-id @form])
      :ok-type :danger
      :ok-text "Yes - Confirm Move-out"})))


(defn move-out-modal-footer
  [account form]
  [:div
   [ant/button
    {:size     :large
     :on-click #(dispatch [:accounts.entry.transition/hide])}
    "Cancel"]
   (if (:editing @form)
     (let [license-id    (get-in account [:active_license :id])
           transition-id (get-in account [:active_license :transition :id])]
       [ant/button
        {:type     :primary
         :size     :large
         :on-click #(dispatch [:accounts.entry/update-move-out! license-id transition-id @form])}
        "Update Move-out Data"])
     [ant/button
      {:type     :danger
       :size     :large
       :disabled (or
                  (nil? (:date @form))
                  (nil? (:written-notice @form)))
       :on-click #(move-out-confirmation account form)}
      "Begin Move-out Process"])])


(def asana-transition-templates
  {:move-out "https://app.asana.com/0/306571089298787/622139719994873"})


(defn move-out-start []
  [:div
   [ant/alert
    {:type        :warning
     :show-icon   true
     :message     "Before you begin:"
     :description "Ensure that you have received written notice from the member stating their intent to move out."}]

   [:br]
   [:div.has-text-centered
    [ant/button
     {:size     :large
      :on-click #(dispatch [:accounts.entry.transition/update :written-notice true])}
     "Written notice has been given"]]])


(defn- move-out-form-item
  [question input]
  [:div
   {:style {:margin-bottom "1em"}}
   question
   input])


(defn- default-moment
  [m]
  (or m (js/moment (.getTime (js/Date.)))))


(defn move-out-additional-form
  [form]
  [:div
   ;; TODO - validate/sanitize this link.
   [move-out-form-item
    [ant/tooltip
     {:title "Link to Google Drive Doc"}
     [:p.bold "Final Walkthrough Notes"]]
    [ant/input
     {:placeholder "paste the google drive link here..."
      :value       (:room-walkthrough-doc @form)
      :on-change   #(dispatch [:accounts.entry.transition/update :room-walkthrough-doc (.. % -target -value)])}]]

   [move-out-form-item
    [ant/tooltip
     {:title     "To be added after Ops has reviewed the final walkthrough details"
      :placement "topLeft"}
     [:p.bold "Security Desposit Refund Amount"]]
    [ant/input-number
     {:style     {:width "50%"}
      :value     (:deposit-refund @form)
      :on-change #(dispatch [:accounts.entry.transition/update :deposit-refund %])}]]])


(defn move-out-form [form]
  [:div
   [move-out-form-item
    [:p.bold "What date is the member moving out?"]
    [form/date-picker
     {:style         {:width "50%"}
      :value         (:date @form)
      :on-change     #(dispatch [:accounts.entry.transition/update :date %])}]]

   ;; TODO - validate/sanitize this link.
   [move-out-form-item
    [:span [:span.bold "Asana Move-out Task"]
     [ant/tooltip
      {:placement "topLeft"
       :title     (r/as-element
                   [:div "Make a copy of the " [:a {:href (:move-out asana-transition-templates) :target "_blank"} "Member Move Out Template"] " Asana task. Paste the link to your copy of that task in this input."])}
      [ant/icon {:type "question-circle"}]]]
    [ant/input
     {:placeholder "paste the asana link here..."
      :value       (:asana-task @form)
      :on-change   #(dispatch [:accounts.entry.transition/update :asana-task (.. % -target -value)])}]]

   (if (:editing @form)
     [move-out-additional-form form]
     [ant/alert
      {:type      :info
       :show-icon true
       :message   "You'll have the opportunity to add more information about this move-out later."}])])


(defn move-out-modal
  [account]
  (let [form (subscribe [:accounts.entry.transition/form-data])]
    [ant/modal
     {:title       (str "Move-out: " (:name account))
      :visible     @(subscribe [:modal/visible? db/transition-modal-key])
      :after-close #(dispatch [:accounts.entry.transition/clear])
      :on-cancel   #(dispatch [:accounts.entry.transition/hide])
      :footer      (r/as-element [move-out-modal-footer account form])}

     (if (nil? (:written-notice @form))
       [move-out-start]
       [move-out-form form])]))


(defn renewal-confirmation
  [account form]
  (let [license (:active_license account)]
    (ant/modal-confirm
     {:title   "Confirm License Renewal"
      :content "Are you sure you want to continue? This action can't easily be undone and will send an email notification to the member."
      :on-ok   #(dispatch [:accounts.entry/renew-license! license @form])
      :ok-type :primary
      :ok-text "Yes - Confirm License Renewal"})))


(defn renewal-modal-footer
  [account form]
  [:div
   [ant/button
    {:size     :large
     :on-click #(dispatch [:modal/hide db/renewal-modal-key])}
    "Cancel"]
   (if (:editing @form)
     (let [license-id    (get-in account [:active_license :id])
           transition-id (get-in account [:active_license :transition :id])]
       [ant/button
        {:type     :primary
         :size     :large
         :on-click #(dispatch [:accounts.entry/update-move-out! license-id transition-id @form])}
        "Update Renewal Info"])
     [ant/button
      {:type     :danger
       :size     :large
       :disabled false ;; TODO - disable when no term?
       :on-click #(renewal-confirmation account form)}
      "Renew License"])])

(def radio-style
  {:display     "block"
   :height      "30px"
   :line-height "30px"})

(defn renewal-modal
  [account]
  (let [form    (subscribe [:accounts.entry.transition/form-data])
        license (:active_license account)]
    [ant/modal
     {:title       (str "Renewal: " (:name account))
      :visible     @(subscribe [:modal/visible? db/renewal-modal-key])
      :after-close #(dispatch [:accounts.entry.transition/clear])
      :on-cancel   #(dispatch [:modal/hide db/renewal-modal-key])
      :footer      (r/as-element [renewal-modal-footer account form])}
     [move-out-form-item
      [:p.bold "What will the member's new license term be?"]
      [ant/select
       {:style         {:width "50%"}
        :default-value "3"
        :on-change     #(dispatch [:accounts.entry.reassign/update-term license (int %)])}
       [ant/select-option {:value "3"} "3 months"]
       [ant/select-option {:value "6"} "6 months"]
       [ant/select-option {:value "12"} "12 months"]]]

     [move-out-form-item
      [:p.bold "Will the member's monthly rate change?"
       [ant/tooltip
        {:placement "right"
         :title     (r/as-element
                     [:div "Please consult with the Operations team before changing the member's rate."])}
        [ant/icon {:type  "question-circle"
                   :style {:margin-left 10}}]]]
      [ant/radio-group
       {:on-change #(dispatch [:accounts.entry.transition/update :rate-changing (.. % -target -value)])
        :disabled  (nil? (:rate @form))
        :value     (:rate-changing @form)}
       [ant/radio (assoc {:value false} :style radio-style) "No - it will not change"]
       [ant/radio (assoc {:value true} :style radio-style) "Yes - it will change to..."
        (when (true? (:rate-changing @form))
          [ant/input-number {:style       {:width       "50%"
                                           :margin-left 10}
                             :size        :small
                             :min         0
                             :formatter   #(str "$"%)
                             :placeholder "new rate..."
                             :value       (:rate @form)
                             :on-change   #(dispatch [:accounts.entry.transition/update :rate %])}])]]]]))

(defn membership-actions [account]
  (when (nil? (:transition (:active_license account)))
    [:div
     [reassign-modal account]
     [ant/button
      {:icon     "swap"
       :on-click #(dispatch [:accounts.entry.reassign/show account])}
      "Reassign"]
     [ant/button
      {:icon     "retweet"
       :on-click #(dispatch [:accounts.entry.renewal/show (:active_license account)])}
      "Renew License"]
     [ant/button
      {:icon     "home"
       :type     :danger
       :ghost    true
       :on-click #(dispatch [:accounts.entry.transition/show (get-in account [:active_license :transition])])}
      "Move-out"]]))


(defn- render-status [_ {status :status}]
  [ant/tooltip {:title status}
   [ant/icon {:class (order/status-icon-class (keyword status))
              :type  (order/status-icon (keyword status))}]])


(defn transition-status-item
  [label value]
  [:div
   [:p.bold label]
   [:p value]])


(defmulti transition-status (fn [_ transition] (:type transition)))


(defmethod transition-status :renewal
  [account transition]
  (let [pname (format/make-first-name-possessive (:name account))
        new-license (:new_license transition)]
      [ant/card
       {:title (str pname "License Renewal")}
       [:div.columns
        [:div.column
         [transition-status-item "Term" (str (:term new-license) " months")]
         [transition-status-item "Duration" (str (format/date-short (:starts new-license)) " - " (format/date-short (:ends new-license)))]]
        [:div.column
         [transition-status-item "Rate" (format/currency (:rate new-license))]]]]))


(defmethod transition-status :move_out
  [account transition]
  (let [pname (format/make-first-name-possessive (:name account))]
    [ant/card
     {:title (str pname "Move-out Information")
      :extra (r/as-element
              [:div
               (when (some? (:asana_task transition))
                 [:a
                  {:href (:asana_task transition)
                   :target "_blank"}
                  [ant/button
                   {:icon "check-square-o"}
                   "Open in Asana"]]) " "
               [ant/button
                {:icon     "edit"
                 :on-click #(dispatch [:accounts.entry.transition/show transition])}
                "Edit"]])}
     [:div.columns
      [:div.column
       [transition-status-item "Move-out date" (format/date-short (:date transition))]
       (when (some? (:room_walkthrough_doc transition))
         [:div
          [:p.bold "Final  Walkthrough Docs"]
          [:p [:a {:href (:room_walkthrough_doc transition) :target "_blank"} "Open in Google Drive"]]])]

      [:div.column
       (when (some? (:deposit_refund transition))
         [transition-status-item "Security Deposit Refund Amount" (format/currency (:deposit_refund transition))])
       (when (some? (:early_termination_fee transition))
         [transition-status-item "Early Termination Fee Amount" (format/currency (:early_termination_fee transition))])]]
     (when (empty? (:asana_task transition))
       [ant/alert
        {:type      :warning
         :show-icon true
         :message   "Please add a link to this member's copy of the Member Move Out Template task in Asana."}])]))


(defn membership-orders-list [account orders]
  [ant/card
   {:title (str (format/make-first-name-possessive (:name account)) "Helping Hands Orders")}
   [ant/table
    (let [service-route #(routes/path-for :services.orders/entry :order-id (.-id %))]
      {:columns     [{:title     ""
                      :dataIndex :status
                      :render    (table/wrap-cljs render-status)}
                     {:title     "Service"
                      :dataIndex :name
                      :render    #(r/as-element
                                   [:a {:href                    (service-route %2)
                                        :dangerouslySetInnerHTML {:__html %1}}])}
                     {:title     "Price"
                      :dataIndex :price
                      :render    (table/wrap-cljs #(if (some? %) (format/currency %) "n/a"))}]
       :dataSource  (map-indexed #(assoc %2 :key %1) orders)
       :pagination  {:size              :small
                     :default-page-size 4}
       :show-header false})]])


(defn membership-view [account]
  (let [license    (most-current-license account)
        is-active  (= :active (:status license))
        transition (:transition (:active_license account))
        orders     @(subscribe [:account/orders (:id account)])]
    [:div.columns
     [move-out-modal account]
     [renewal-modal account]
     [:div.column
      [membership/license-summary license
       (when is-active {:content [membership-actions account]})]]
     [:div.column
      (when is-active [status-bar account])
      (when (not (nil? transition)) [transition-status account transition])
      (when is-active [membership-orders-list account orders])]]))



(defn- menu-item [role key]
  [ant/menu-item
   {:key key :disabled (not (db/allowed? role key))}
   (string/capitalize key)])


(defn menu [{role :role}]
  (let [selected (subscribe [:accounts.entry/selected-tab])]
    [ant/menu {:mode          :horizontal
               :selected-keys [@selected]
               :on-click      #(dispatch [:accounts.entry/select-tab (aget % "key")])}
     (map
      (partial menu-item role)
      ["membership" "payments" "application" "notes"])]))


;; entrypoint ===================================================================


(defmethod content/view :accounts/entry
  [{{account-id :account-id} :params, path :path}]
  (let [{:keys [email phone] :as account} @(subscribe [:account (tb/str->int account-id)])
        selected                          (subscribe [:accounts.entry/selected-tab])
        is-loading                        (subscribe [:ui/loading? :account/fetch])]
    (if (or @is-loading (nil? account))
      (loading/fullpage :text "Fetching account...")
      [:div
       [:div.columns
        [:div.column.is-three-quarters
         (typography/view-header (:name account) (subheader account))]
        [:div.column [contact-info account]]]

       [:div.columns
        [:div.column
         [menu account]]]

       (case @selected
         "membership"  [membership-view account]
         "payments"    [payments-view account]
         "application" [application-view account]
         "notes"       [notes-view account]
         [:div])])))
