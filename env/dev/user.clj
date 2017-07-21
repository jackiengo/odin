(ns user
  (:require [admin.core]
            [admin.config :as config :refer [config]]
            [admin.datomic :refer [conn]]
            [admin.seed :as seed]
            [clojure.spec.test :as stest]
            [clojure.tools.namespace.repl :refer [refresh]]
            [figwheel-sidecar.repl-api :as ra]
            [mount.core :as mount :refer [defstate]]
            [taoensso.timbre :as timbre]))


(timbre/refer-timbre)


;; =============================================================================
;; Reloaded
;; =============================================================================


(defn- in-memory-db? []
  (= "datomic:mem://localhost:4334/starcity" (config/datomic-uri config)))


(defstate seed
  :start (when (in-memory-db?)
           (timbre/debug "seeding dev database...")
           (seed/seed conn)))


(def start #(mount/start-with-args {:env :dev}))


(def stop mount/stop)


(defn go []
  (start)
  (stest/instrument)
  :ready)


(defn reset []
  (stop)
  (refresh :after 'user/go))


;; =============================================================================
;; Figwheel
;; =============================================================================


(defn start-figwheel! []
  (when-not (ra/figwheel-running?)
    (timbre/debug "starting figwheel server...")
    (ra/start-figwheel! "admin")))


(defn cljs-repl []
  (ra/cljs-repl "admin"))
