(ns com.adgoji.vistarmedia
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as hc]
   [hato.middleware :as hmw])
  (:import java.net.http.HttpClient
           java.util.concurrent.ExecutorService
           java.util.concurrent.Executors))

(def ^:private default-base-url "https://platform-api.vistarmedia.com/api")
(def ^:private session-path "/session")

(defn- decode-json
  [body charset]
  (with-open [reader (io/reader body :encoding charset)]
    (json/read reader
               {:key-fn     csk/->kebab-case-keyword
                :eof-error? false
                :eof-value  nil})))

(defn- json-response?
  [resp]
  (some-> resp
          :content-type
          (name)
          (str/includes? "json")))

(defn- coerce-json-body
  "Custom JSON coercer for hato that uses data.json."
  [_req resp]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (if (json-response? resp)
      (update resp :body decode-json charset)
      (update resp :body #(some-> % (slurp :encoding charset))))))

(defmethod hmw/coerce-response-body ::json
  [req resp]
  (coerce-json-body req resp))

(defn- encode-json
  [body]
  (when body
    (json/write-str body {:key-fn csk/->snake_case_string})))

(defn- wrap-json-request
  [client]
  (fn
    ([req]
     (client (update req :body encode-json)))
    ([req respond raise]
     (client (update req :body encode-json)
             respond
             raise))))

(def default-middleware
  (conj hmw/default-middleware wrap-json-request))

(defn new-client
  "Returns new VistarMedia DSP API client.

  The only mandatory parameter is `credential` which is a map with
  `:email` and `:password` keys.  The user must have API access.

  Optional parameters:

  - `base-url` - in case you want to override it and use another
    environment.
  - `http-opts` - overrides for the internal hato HTTP client.  The
    options are merged with the default options map, but
    `:cookie-policy` is always set to `:all` (it's necessary for
    authentication to work).
  - `wrap-request` allows to provide a wrapper for a request call.
    This might be useful to setup default retry or rate limiter
    policies.  The wrapper should accept a thunk that takes no args and
    returns the full HTTP response, including headers, status code etc."
  [{::keys! [credential]
    ::keys  [http-opts base-url wrap-request]}]
  (let [executor (when-not (:executor http-opts)
                   (Executors/newVirtualThreadPerTaskExecutor))
        client   (hc/build-http-client
                  (-> {:connect-timeout 10000
                       :redirect-policy :always
                       :version         :http-2
                       :cookie-policy   :all}
                      (into (dissoc http-opts :cookie-policy))
                      (cond-> executor (assoc :executor executor))))]
    {::credential   credential
     ::http-client  client
     ::base-url     (or base-url default-base-url)
     ::wrap-request wrap-request
     ::executor     executor}))

(defn close-client
  "Close the internal HTTP connection pool and executor.

  NOTE: if custom executor was provided during client initialization,
  it won't be closed."
  [{::keys [http-client executor]}]
  (HttpClient/.close http-client)
  (when executor
    (ExecutorService/.close executor)))

(defn- request
  [method path params {::keys [http-client base-url wrap-request]}]
  (let [call (fn request-call
               []
               (hc/request (cond-> {:method            method
                                    :url               (str base-url path)
                                    :http-client       http-client
                                    :content-type      :json
                                    :as                ::json
                                    :coerce            :always
                                    :middleware        default-middleware
                                    :throw-exceptions? false}
                             (:body params)
                             (assoc :body (:body params))
                             (:query-params params)
                             (assoc :query-params (:query-params params)))))]
    (if wrap-request
      (wrap-request call)
      (call))))

(def ops
  "Map of all supported operations to paths and required HTTP methods."
  {::advertisers-list         [:get "/dsp/v1/advertisers"]
   ::advertiser-create        [:post "/dsp/v1/advertisers"]
   ::advertiser-get           [:get "/dsp/v1/advertisers/{id}"]
   ::insertion-orders-list    [:get "/dsp/v1/insertion_orders"]
   ::insertion-orders-update  [:patch "/dsp/v1/insertion_orders"]
   ::insertion-orders-create  [:post "/dsp/v1/insertion_orders"]
   ::insertion-order-get      [:get "/dsp/v1/insertion_orders/{id}"]
   ::insertion-order-update   [:patch "/dsp/v1/insertion_orders/{id}"]
   ::campaigns-list           [:get "/dsp/v1/campaigns"]
   ::campaigns-update         [:patch "/dsp/v1/campaigns"]
   ::campaigns-create         [:post "/dsp/v1/campaigns"]
   ::campaign-get             [:get "/dsp/v1/campaigns/{id}"]
   ::campaign-update          [:patch "/dsp/v1/campaigns/{id}"]
   ::campaign-set-targetings  [:put "/dsp/v1/campaigns/{campaign-id}/targetings"]
   ::campaign-get-targetings  [:get "/dsp/v1/campaigns/{campaign-id}/targetings"]
   ::creatives-list           [:get "/dsp/v1/creatives"]
   ::creatives-update         [:patch "/dsp/v1/creatives"]
   ::creative-get             [:get "/dsp/v1/creatives/{id}"]
   ::creative-update          [:patch "/dsp/v1/creatives/{id}"]
   ::creative-categories-list [:get "/dsp/v1/creative_categories"]
   ::creative-category-get    [:get "/dsp/v1/creative_categories/{id}"]
   ::publishers-list          [:get "/dsp/v1/publishers"]
   ::publisher-get            [:get "/dsp/v1/publishers/{id}"]
   ::venues-list              [:get "/dsp/v1/venues"]
   ::venue-get                [:get "/dsp/v1/venues/{id}"]
   ::venue-types-list         [:get "/dsp/v1/venue_types"]
   ::venue-type-get           [:get "/dsp/v1/venue_types/{id}"]})

(defn- login
  [client]
  (let [params {:body (::credential client)}
        resp   (request :post session-path params client)]
    (when (not= 200 (:status resp))
      (throw (ex-info "Authorization failed"
                      {:client :vistarmedia})))))

(defn- check-op-status
  [resp op]
  (if (<= 400 (:status resp))
    (throw (ex-info "Operation failed"
                    {:status (:status resp)
                     :op     op
                     :error  (:body resp)}))
    (:body resp)))

(defn- normalize-path
  [path opts]
  (reduce (fn [s [param param-name]]
            (if-let [param-value (get opts (keyword param-name))]
              (str/replace s param (str param-value))
              (throw (ex-info "Missing parameter"
                              {:param-name param-name}))))
          path
          (re-seq #"\{(.+?)\}" path)))

(defn invoke
  "Invoke the `op` operation and return the response body as Clojure map.

  Authentication is handled automatically, if operation itself returns
  403 error, login operation is called and the requested operation is
  retried.  If login operation fails, an exception is thrown.

  `payload` can optionally include `:query-params`, `:body` or named
  path parameters.  If requested operation is a `:get` operation, only
  `:query-params` is passed to the request, otherwise, only `:body`.
  If operation's path contains any named parameters, those parameters
  must be present in the `payload` with the same name, for example if
  path is '/advertisers/{id}' then payload must contain `:id` keys
  with a valid advertiser ID.  Inspect the [ops] map to figure out
  operation's method and presence of named path parameters.

  Optionally a wrapper can be provided for the requested operation, it
  will temporary override the global wrapper stored in the `client`.
  This should be used carefully, especially with rate limiters since
  it can ignore global `client` rate limiter.  The provided wrapper
  has no effect on login call."
  [client {::keys [op payload wrap-request]}]
  (if-let [[method path-raw] (get ops op)]
    (let [cl     (cond-> client
                   wrap-request (assoc ::wrap-request wrap-request))
          path   (normalize-path path-raw payload)
          params (if (= :get method)
                   (select-keys payload [:query-params])
                   (select-keys payload [:body]))
          resp   (request method path params cl)]
      (if (= 403 (:status resp))
        ;; Use the original client here.  Redefined wrap-request
        ;; should only affect the requested operation.
        (do (login client)
            (-> (request method path params cl)
                (check-op-status op)))
        (check-op-status resp op)))
    (throw (ex-info "Unsupported operation"
                    {:client :vistarmedia
                     :op     op}))))
