(ns member.profile.payments.sources.views.forms
  (:require [antizer.reagent :as ant]
            [iface.utils.validation :as validation]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [toolbelt.core :as tb]))


(def ^:private form-style
  {:label-col   {:span 7}
   :wrapper-col {:span 10}})


(defn bitcoin-account []
  [:div
   [:div.card
    [:div.card-content.align-center
     [:div.width-90.center
      [:h3 "Deposit Address"]
      [:pre.is-size-4 "1F1tAaz5x1HUXrCNLbtMDqcw6o5GNn4xqX"]
      [:br]
      [:p.is-size-6. "BTC sent to this address will credit toward your Starcity account balance, which you can then use to make payments."]]]]
   [:hr]
   [:div.align-right
    [:a.button {:on-click #(dispatch [:payment.sources.add/hide])} "Cancel"]
    [:a.button.is-primary "OK"]]])


(defn- form-item-props [label]
  (merge form-style {:label label}))


(defn- form-item [{:keys [key label ant-id input-props rules]}]
  (let [form (ant/get-form)]
    [ant/form-item (form-item-props label)
     (ant/decorate-field form ant-id {:rules rules} [ant/input input-props])]))


(def ^:private bank-form-items
  [{:key         :account-holder
    :label       "Name of Account Holder"
    :ant-id      "Full name"
    :rules       [{:required true}]
    :input-props {:placeholder "Jane S. Doe"}}
   {:key         :routing-number
    :label       "Routing #"
    :ant-id      "Routing number"
    :rules       [{:required true}
                  {:len 9}]
    :input-props {:placeholder "110000000"}}
   {:key         :account-number
    :label       "Account #"
    :ant-id      "Account number"
    :rules       [{:pattern "^(\\d+)(\\d+|-)*$"
                   :message "Account number should begin with a digit, and contain only digits and hyphens."}
                  {:required true}
                  {:max 20}]
    :input-props {:placeholder "000123456789"}}])


(defn- bank-account-form []
  (let [form      (ant/get-form)
        on-change (fn [k] #(dispatch [:payment.sources.add.bank/update! k (.. % -target -value)]))]
    [ant/form
     (map-indexed
      (fn [idx {key :key :as item}]
        (-> (assoc-in item [:input-props :on-change] (on-change key))
            (form-item)
            (with-meta {:key idx})))
     bank-form-items)]))


(def ^:private bank-account-desc
  "Upon adding your bank account, we'll make two small transactions to verify
  ownership. Your account will be ready to use after you've verified the amounts
  contained in those transactions. (Note: It may take up to 2 days for these
  transactions to appear.)")


(defn- submit-when-valid
  [form event]
  (let [submit* (fn [errors _] (when (nil? errors) (dispatch event)))]
    #(ant/validate-fields form submit*)))


(defn bank-account []
  (let [is-submitting (subscribe [:ui/loading? :payment.sources.add/bank])]
    (fn []
      (let [form (ant/get-form)]
       [:div
        (bank-account-form)
        [:p.pad.text-red.fs2 [:b bank-account-desc]]
        [:hr]
        [:div.align-right
         [ant/button
          {:on-click #(dispatch [:modal/hide :payment.source/add])
           :size     :large}
          "Cancel"]
         [ant/button
          {:type     :primary
           :size     :large
           :loading  @is-submitting
           :on-click (submit-when-valid form [:payment.sources.add.bank/submit!])}
          "Add Bank Account"]]]))))
