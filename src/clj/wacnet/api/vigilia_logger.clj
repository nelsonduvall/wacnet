(ns wacnet.api.vigilia-logger
  (:require [bacure.core :as b]
            [yada.resource :refer [resource]]
            [bidi.bidi :refer [path-for]]
            [schema.core :as s]
            [ring.swagger.schema :as rs]
            [yada.yada :as yada]
            [clojure.set :as cs]
            [vigilia-logger.scan :as scan]
            [vigilia-logger.timed :as timed]
            [wacnet.api.util :as u]))




(declare api-route
         configs)


(defn scanning-state-resp [ctx]
  {:href (u/make-link ctx)
   :logging? (timed/is-logging?)
   :scanning-state (-> @scan/scanning-state
                       (update-in [:start-time] #(-> % .getMillis .toString))
                       (update-in [:end-time] #(-> % .getMillis .toString))
                       (assoc :logging (timed/is-logging?)
                              :local-logs (count (scan/find-unsent-logs))))
   :configs {:href (u/make-link ctx (str "/" (path-for api-route configs)))}})

(def scanning-state
  (resource 
   {:produces [{:media-type u/produced-types
                :charset "UTF-8"}]
    :consumes [{:media-type u/consumed-types
                :charset "UTF-8"}]
    :access-control {:allow-origin "*"}
    :methods {:post {:description "Start or stop the Vigilia logging."
                     :swagger/tags ["Vigilia"]
                     :parameters {:body  {:logging (rs/field s/Bool {:description "True will start the logging, false will stop it."})}}
                     :response (fn [ctx]
                                 (let [logging (some-> ctx :parameters :body :logging)]
                                   (if logging 
                                     (timed/restart-logging)
                                     (timed/stop-logging))
                                   (scanning-state-resp ctx)))}
              :get {:description (str "Various information about the state of the current scan (if any). Fields include: \n"
                                  "- scanning? \n"
                                  "- start-time \n"
                                  "- end-time \n"
                                  "- scanning-time-ms \n"
                                  "- ids-to-scan \n"
                                  "- ids-scanning \n"
                                  "- ids-scanned \n")
                    :swagger/tags ["Vigilia"]
                    :response scanning-state-resp}}}))

(def expected-keys
  [:min-range
   :max-range
   :project-id
   :api-root
   :logger-id
   :logger-version
   :logger-key
   :id-to-remove
   :id-to-keep
   :time-interval
   :criteria-coll
   :object-delay])



(s/defschema Configs
  {(s/optional-key :api-root) s/Str
   (s/optional-key :logger-id) s/Str
   (s/optional-key :logger-version) s/Str
   (s/optional-key :logger-key) s/Str
   (s/optional-key :project-id) s/Str
   (s/optional-key :min-range) s/Int
   (s/optional-key :max-range) s/Int
   (s/optional-key :id-to-remove) [s/Int]
   (s/optional-key :id-to-keep) [s/Int]
   (s/optional-key :time-interval) s/Int
   (s/optional-key :criteria-coll) s/Any
   (s/optional-key :object-delay) s/Int})

(def configs
  (let [response-map-fn (fn [ctx]                
                          (let [configs (scan/get-logger-configs)]
                            {:href (u/make-link ctx)
                             :vigilia {:href (when-let [p-id (:project-id configs)] 
                                               (str (->> (scan/get-api-root)
                                                         (re-find #"(.*)/api/")
                                                         (last))
                                                    "/v/" p-id))}
                             :configs (merge (into {} (for [k expected-keys]
                                                        [k nil]))
                                             configs)}))]
    (resource 
     {:produces [{:media-type u/produced-types
                  :charset "UTF-8"}]
      :consumes [{:media-type u/consumed-types
                  :charset "UTF-8"}]
      :access-control {:allow-origin "*"}
      :methods {:get {:description "Logger configurations"
                      :swagger/tags ["Vigilia"]
                      :response response-map-fn}
                :delete {:description (str "Delete the current logger configurations. "
                                           "This will prevent Wacnet from automatically scanning devices on boot.")
                         :swagger/tags ["Vigilia"]
                         :response (fn [ctx]
                                     (scan/delete-logger-configs!))}
                :post {:description "Update the provided fields in the Vigilia logger configs."
                       :swagger/tags ["Vigilia"]
                       :parameters {:body Configs}
                       :response (fn [ctx]
                                   (when-let [new-configs (some-> ctx :parameters :body)]
                                     (scan/save-logger-configs! (->> (for [[k v] new-configs]
                                                                       (when-not (when (or (coll? v) (string? v))
                                                                                   (empty? v))
                                                                         [k v]))
                                                                     (remove nil?)
                                                                     (into {}))))
                                   (response-map-fn ctx))}}})))

(def test-api-root
  (resource 
   {:produces [{:media-type u/produced-types
                :charset "UTF-8"}]
    :consumes [{:media-type u/consumed-types
                :charset "UTF-8"}]
    :access-control {:allow-origin "*"}
    :methods {:get {:description (str "Test communication with a remote Vigilia server. Will use the default API url or "
                                      "the one provided as a query argument.")
                    :swagger/tags ["Vigilia"]
                    :parameters {:query {(s/optional-key :api-root)
                                         (rs/field s/Str {:description "Alternative API url"})}}
                    :response (fn [ctx]
                                (let [api-root (or (some-> ctx :parameters :query :api-root)
                                                   (scan/get-api-root))]
                                  {:can-connect? (scan/can-connect? api-root)
                                   :api-root api-root}))}}}))

(def test-credentials
  (resource 
   {:produces [{:media-type u/produced-types
                :charset "UTF-8"}]
    :consumes [{:media-type u/consumed-types
                :charset "UTF-8"}]
    :access-control {:allow-origin "*"}
    :methods {:get {:description (str "Test credentials with a remote Vigilia server. Will use the default "
                                      "currently saved credentials or "
                                      "the one provided as query arguments.")
                    :swagger/tags ["Vigilia"]
                    :parameters {:query {(s/optional-key :project-id) s/Str
                                         (s/optional-key :logger-key) s/Str}}
                    :response (fn [ctx]
                                (let [project-id (some-> ctx :parameters :query :project-id)
                                      logger-key (some-> ctx :parameters :query :logger-key)
                                      credentials (if (and project-id logger-key)
                                                    {:project-id project-id :logger-key logger-key}
                                                    (select-keys (scan/get-logger-configs)
                                                                 [:project-id :logger-key]))]
                                  {:credentials-valid? (scan/credentials-valid? (:project-id credentials)
                                                                                (:logger-key credentials))
                                   :credentials credentials}))}}}))



(def api-route
  ["vigilia" [;["" :none]
              ["/logger" [["" scanning-state]
                          ["/tests" [["/api-root" test-api-root]
                                     ["/credentials" test-credentials]]]
                          ["/configs" configs]
                          ]]]])