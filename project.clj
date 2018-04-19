(defproject odin "1.9.1"
  :description "The all-dashboard."
  :url "http://my.starcity.com"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.671"]
                 [org.clojure/tools.reader "1.2.1"]
                 [org.clojure/core.async "0.3.443"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [org.clojure/test.check "0.9.0"]
                 ;; Web
                 [bidi "2.1.3"]
                 [ring "1.6.3"]
                 [starcity/facade "0.4.0"
                  :exclusions [com.google.guava/guava]]
                 [starcity/datomic-session-store "0.1.0"]
                 [starcity/customs "1.0.0"
                  :exclusions [com.google.guava/guava
                               com.fasterxml.jackson.core/jackson-core]]
                 [optimus "0.20.1"]
                 [http-kit "2.2.0"]
                 [compojure "1.6.0"
                  :exclusions [ring/ring-core]]
                 [ring-middleware-format "0.7.2"
                  :exclusions [ring/ring-core]]
                 ;; Other
                 [kami "0.1.0" :exclusions [starcity/blueprints]]
                 ;; GraphQL
                 [com.walmartlabs/lacinia "0.25.0"]
                 [vincit/venia "0.2.3"]
                 ;; CLJS
                 [tongue "0.2.3"]
                 [antizer "0.2.2"
                  :exclusions [com.google.guava/guava cljsjs/react cljsjs/antd org.clojure/clojurescript]]
                 [re-frame "0.9.4"
                  :exclusions [com.google.guava/guava cljsjs/react org.clojure/clojurescript]]
                 [reagent "0.7.0"
                  :exclusions [org.clojure/clojurescript]]
                 [cljsjs/antd "2.13.4-0"
                  :exclusions [cljsjs/react cljsjs/react-dom]]
                 [cljsjs/highcharts-css "5.0.10-0"]
                 [cljsjs/filesaverjs "1.3.3-0"]
                 [cljsjs/libphonenumber "8.4.1-1"]
                 [cljsjs/react "15.6.1-0"]
                 [cljsjs/react-dom "15.6.1-0"]
                 [starcity/accountant "0.2.0"
                  :exclusions [org.clojure/clojurescript
                               org.clojure/core.async]]
                 [day8.re-frame/http-fx "0.1.5"
                  :exclusions [com.google.guava/guava
                               org.apache.httpcomponents/httpclient
                               org.clojure/clojurescript]]
                 [starcity.re-frame/stripe-fx "0.1.0"
                  :exclusions [org.clojure/clojurescript]]
                 [cljsjs/moment "2.17.1-1"]
                 [akiroz.re-frame/storage "0.1.2"]
                 ;; DB
                 [starcity/blueprints "2.4.3"
                  :exclusions [com.datomic/datomic-free
                               com.google.guava/guava]]
                 ;; dep resolution
                 [com.google.guava/guava "21.0"]
                 [cljs-ajax "0.7.3"
                  :exclusions [org.clojure/core.async]]
                 ;; Util
                 [com.cemerick/url "0.1.1"]
                 [mount "0.1.11"]
                 [aero "1.1.2"]
                 [starcity/drawknife "1.0.0"
                  :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.taoensso/timbre "4.10.0"]
                 [starcity/ribbon "0.11.0"]
                 [starcity/toolbelt-async "0.4.0"]
                 [starcity/toolbelt-core "0.4.0"]
                 [starcity/toolbelt-date "0.3.0"]
                 [starcity/toolbelt-datomic "0.5.0"]
                 [starcity/toolbelt-re-frame "0.1.0"
                  :exclusions [org.clojure/clojurescript]]
                 [enlive "1.1.6"]]

  :jvm-opts ["-server"
             "-Xmx4g"
             "-XX:+UseCompressedOops"
             "-XX:+DoEscapeAnalysis"
             "-XX:+UseConcMarkSweepGC"]

  :repositories {"releases" {:url        "s3://starjars/releases"
                             :username   :env/aws_access_key
                             :passphrase :env/aws_secret_key}}

  :plugins [[lein-cljsbuild "1.1.7" :exclusions [org.clojure/clojurescript org.clojure/tools.reader]]
            [s3-wagon-private "1.2.0"]]

  :repl-options {:init-ns user}

  :clean-targets ^{:protect false} ["resources/public/js/cljs" :target-path])
