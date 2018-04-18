(ns member.services.db
  (:require [member.routes :as routes]
            [clojure.string :as string]))


(def path
  ::services)


(def modal
  :member.services/add-service)


(def default-params
  {:category :cleaning})


;; Should submited ps requests be held here? are these keys appropriate for them?
;; We should probably seed some items in them
(def default-value
  {path {:params        default-params
         :adding        nil
         :cart          {}
         ;; :last-modified ""
         :form-data     []
         :orders        []}})


(defmulti params->route (fn [page params] page))


(defmethod params->route :services/book [page params]
  (let [params (-> (select-keys params [:category])
                   (update :category name))]
    (routes/path-for page :query-params params)))


(defmethod params->route :services/manage [page params]
  (routes/path-for page))


(defmulti parse-query-params (fn [page params] page))


(defmethod parse-query-params :services/book [page params]
  (update params :category keyword))


(defmethod parse-query-params :services/manage [page params]
  params)


(defn can-add-service?
  [fields]
  (->> (filter :required fields)
       (reduce
        (fn [acc field]
          (let [v (:value field)]
            (and acc (if (string? v)
                       (not (string/blank? v))
                       v))))
        true)))
