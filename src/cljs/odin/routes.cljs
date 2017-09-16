(ns odin.routes
  (:require [accountant.core :as accountant]
            [bidi.bidi :as bidi]
            [re-frame.core :refer [dispatch reg-fx]]
            [toolbelt.core :as tb]
            [cemerick.url :as c]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [odin.utils.dispatch :as dispatch]))
            ;;[ring.middleware.params :as ring]))


(def app-routes
  [""
   [["/metrics" :metrics]

    ["/orders" :orders]

    ["/profile" [["" :profile/membership]
                 ;; NOTE: Unnecessary because this is the default
                 ;; ["/membership" :profile/membership]
                 ["/contact" :profile/contact]

                 ["/payments"
                  [[""         :profile.payment/history]
                   ["/sources" :profile.payment/sources]]]

                 ["/settings"
                  [["/change-password" :profile.settings/change-password]]]]]

    ["/logout" :logout]

    [true :home]]])


(defmulti dispatches
  (fn [{:keys [requester page]}]
    (dispatch/role-dispatch dispatches (:role requester) page)))

(defmethod dispatches :default [route] [])


(def ^:private dummy-base
  "http://foo.com")


(defn baseify-uri
  "Adds a fake protocol and domain to URI string,
  for Cemerick's url function to interpret it properly."
  [uri]
  (if (nil? (re-find #"http" uri))
    (str dummy-base uri)
    uri))


(defn unbaseify-uri
  "Remove the fake protocol / domain from URI. See above."
  [uri]
  (string/replace uri (re-pattern dummy-base) ""))


(defn uri
  [uri]
  (c/url (baseify-uri uri)))


;;(def ^:private testpath "/profile/payments/sources?source-id=card_1B2Rq4IvRccmW9nOLsguUdpe?source-id=card_1AV6tzIvRccmW9nOhQsWMTuv")
;;(def ^:private testpath2 "/profile/payments/sources?source-id=card_1B2Rq4IvRccmW9nOLsguUdpe&foo=bar?source-id=card_1AV6tzIvRccmW9nOhQsWMTuv&bar=baz")
;;
;;(defn de-duplicate-params
;;  "Sometimes Cemerick will add multiple parameters of the same type to our URL.
;;   This function de-duplicates the string, prioritizing LATER parameters."
;;  [path]
;;  (tb/log (c/url (baseify-uri path))))
;;  ;;(tb/log (string/split path "?")))
;;
;;(de-duplicate-params testpath)
;;(de-duplicate-params testpath2)

(defn parse-query-params
  "Parses query parameters from a URL and yields them as a Clojure map."
  [path]
  (walk/keywordize-keys (:query (uri path))))


(defn hook-browser-navigation!
  "Wire up the bidi routes to the browser HTML5 navigation."
  [routes]
  (accountant/configure-navigation!
   {:nav-handler  (fn [path]
                    (let [match  (bidi/match-route app-routes path)
                          page   (:handler match)
                          params (merge (parse-query-params path)
                                   (:route-params match))]
                      (dispatch [:route/change page params])))
    :path-exists? (fn [path]
                    (boolean (bidi/match-route  app-routes path)))})
  (accountant/dispatch-current!))


(defn append-query-params
  [path params]
  (if-let [query-params (:query-params params)]
    (-> (uri path)
        (assoc :query query-params)
        str)
    path))


(defn get-route-params
  [params]
  (dissoc params :query-params))


(defn path-for
  "Produce the path (URI) for `key`."
  ([route]
   (path-for route {}))
  ([route & {:as params}]
   (-> (bidi/path-for app-routes route (get-route-params params))
       (append-query-params params)
       unbaseify-uri)))


(reg-fx
 :route
 (fn [new-route]
   (let [parsed (uri new-route)]
     (if-let [query (:query parsed)]
       (accountant/navigate! (:path parsed) query)
       (accountant/navigate! new-route)))))
