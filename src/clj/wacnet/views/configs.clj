(ns wacnet.views.configs
  (:require [compojure.core :refer [defroutes GET POST]]
            [clojure.walk :as walk]
            [wacnet.views.common :refer [layout]]
            [vigilia-logger.timed :as timed]
            [bacure.core :as bac]
            [bacure.local-device :as ld]
            [bacure.local-save :as save]
            [bacure.read-properties-cached :as rpc]
            [bacure.network :as net]
            [hiccup.page :as hp]
            [hiccup.element :as he]
            [hiccup.form :as hf]
            [noir.session :as session]))

(defn read-map [m]
  (binding [*read-eval* false]
    (let [f (fn [[k v]] [k (read-string v)])]
      (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m))))

(defn read-config
  "Convert the strings in the config map into numbers."[config]
  (let [broadcast-address (:broadcast-address config)
        local-address (:local-address config)
        read-config (-> (dissoc config :broadcast-address :local-address) ;;ip addresses must stay strings
                        read-map)]
    (assoc read-config
      :broadcast-address broadcast-address
      :local-address local-address)))

(defn reset-with-config
  "Reset the local-device with the new configs."[config]
  (session/put! :msg (str "Configurations updated. "
                          (when (timed/is-logging?) "Vigilia logging stopped.")))
  (timed/stop-logging)
  (ld/reset-local-device (read-config config))
  (ld/save-local-device-backup)
  (rpc/set-cache-ttl! (rpc/get-cache-ttl))
  (bac/boot-up))

(defn reset-default
  "Erased any saved config and reset to default."
  []
  (session/put! :msg (str "Configurations cleared. "
                          (when (timed/is-logging?) "Vigilia logging stopped.")))
  (timed/stop-logging)
  (save/delete-configs)
  (ld/clear-all!)
  (rpc/set-cache-ttl! (rpc/get-cache-ttl))
  (bac/boot-up))

(defn config-to-bootstrap [config]
  (for [m config]
    (let [n (name (key m))]
      [:div.form-group
       [:label.control-label.col-md-4 n]
       [:div.col-md-8
        (let [v (val m)]
          [:input.form-control {:type (if (number? v) :number :text)
                                :name n :value v
                                :required true}])]])))

(defn make-configs-forms []
  (-> (merge {:device-id 1 :port 47808 
              :broadcast-address (net/get-broadcast-address (net/get-any-ip))
              :local-address "0.0.0.0"
              :cache-ttl (rpc/get-cache-ttl) }
             (ld/local-device-backup))
      (select-keys [:device-id :port :broadcast-address :local-address
                    :apdu-timeout :apdu-segment-timeout :cache-ttl])
      config-to-bootstrap))

(defn local-interfaces []
  [:p {:style "margin-top:2em;"}
    [:h2 "Local interfaces"]
    (->> (net/interfaces-and-ips)
         (map #(vector :li [:b (:interface %)] ": " 
                       (clojure.string/join ", " 
                                            (:ips %))
                       (str "\nBroadcast: " (:broadcast-address %)))))])

(defn config-page [& msg]
  [:div.container
   [:div.hero-unit
    [:h1 "Local device configurations"]
    (when msg [:span.label.label-success (first msg)])
    [:form.form-horizontal {:method "POST" :action "" :role "form"}
     (make-configs-forms)
     [:div.text-center
      (hf/submit-button {:class "btn btn-primary" :style "margin-right: 1em;"}
                        "Validate and reinitialize the local device!")
      (he/link-to (hiccup.util/url "/configs" {:reset true})
                  [:div.btn.btn-danger "Reset default"])]]]
    (when-let [configs (save/get-configs)]
      [:div.text-center {:style "margin-top:2em;"}
       [:span.label.label-warning "You are using a saved config file."]
       (when (not= (:local-address configs) "0.0.0.0")
         [:div.text-danger "The local address should (almost) always be 0.0.0.0 ."])])
   [:hr]
   (local-interfaces)])
  
(defroutes configs-routes
  (GET "/configs" [reset]
    (when reset
      (reset-default))
    (layout
     (config-page (when reset "cleared"))))
       
  (POST "/configs" req
    (do (reset-with-config (-> req :params ))
        (layout
         (config-page "updated")))))
