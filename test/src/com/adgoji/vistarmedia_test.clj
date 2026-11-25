(ns com.adgoji.vistarmedia-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.adgoji.vistarmedia :as vistarmedia]
   [hato.client :as hc]
   [matcher-combinators.test])
  (:import java.io.ByteArrayInputStream
           java.util.concurrent.ExecutorService
           java.util.concurrent.Executors))

(def ^:private test-credential
  {:email    "me@example.com"
   :password "123456"})

(deftest new-client-test
  (testing "should throw if no credential"
    (is (thrown-with-msg? IllegalArgumentException
                          #"Missing required key: :com.adgoji.vistarmedia/credential"
                          #_:clj-kondo/ignore
                          (vistarmedia/new-client {}))))
  (testing "should create an instance without optional parameters"
    (is (map? (vistarmedia/new-client {::vistarmedia/credential test-credential}))))
  (testing "should never override cookie-policy"
    (let [call-args (atom nil)]
      (with-redefs [hc/build-http-client (fn [args] (reset! call-args args))]
        (vistarmedia/new-client {::vistarmedia/credential test-credential
                                 ::vistarmedia/http-opts  {:cookie-policy :never}})
        (is (= :all (:cookie-policy @call-args)))))))

(defn- mock-request
  [calls response]
  (fn request
    [args]
    (swap! calls conj args)
    response))

(defn- test-client
  ([]
   (test-client {}))
  ([opts]
   (vistarmedia/new-client
    (into {::vistarmedia/credential test-credential} opts))))

(defn- stream
  [^String s]
  (ByteArrayInputStream/new (.getBytes s "UTF-8")))

(defn- coerce
  [resp]
  (#'vistarmedia/coerce-json-body {} resp))

(deftest coerce-json-body-test
  (testing "should decode JSON body with kebab-case keys"
    (is (= {:total-size 1 :insertion-orders [{:id "a"}]}
           (:body (coerce {:status       200
                           :content-type :application/json
                           :body         (stream "{\"total_size\":1,\"insertion_orders\":[{\"id\":\"a\"}]}")})))))
  (testing "should return nil for an empty JSON body"
    (is (nil? (:body (coerce {:status       204
                              :content-type :application/json
                              :body         (stream "")})))))
  (testing "should return nil for a whitespace-only JSON body"
    (is (nil? (:body (coerce {:status       200
                              :content-type :application/json
                              :body         (stream "  \n")})))))
  (testing "should decode +json content types"
    (is (= {:error-message "boom"}
           (:body (coerce {:status       400
                           :content-type :application/problem+json
                           :body         (stream "{\"error_message\":\"boom\"}")})))))
  (testing "should return non-JSON body as a string"
    (is (= "<html>Bad Gateway</html>"
           (:body (coerce {:status       502
                           :content-type :text/html
                           :body         (stream "<html>Bad Gateway</html>")})))))
  (testing "should not fail on missing body and content type"
    (is (nil? (:body (coerce {:status 204 :body nil}))))))

(deftest encode-json-test
  (testing "should not encode nil body"
    (is (nil? (#'vistarmedia/encode-json nil))))
  (testing "should encode body with snake_case keys"
    (is (= "{\"advertiser_id\":\"1\",\"start_date\":\"2026-01-01\"}"
           (#'vistarmedia/encode-json {:advertiser-id "1" :start-date "2026-01-01"}))))
  (testing "middleware should leave a missing body as nil"
    (let [seen (atom nil)
          mw   (#'vistarmedia/wrap-json-request (fn [req] (reset! seen req)))]
      (mw {:method :get})
      (is (nil? (:body @seen))))))

(deftest executor-test
  (testing "should create and expose own executor by default"
    (let [call-args (atom nil)]
      (with-redefs [hc/build-http-client (fn [args] (reset! call-args args))]
        (let [client   (test-client)
              executor (::vistarmedia/executor client)]
          (try
            (is (instance? ExecutorService executor))
            (is (identical? executor (:executor @call-args)))
            (finally (.close ^ExecutorService executor)))))))
  (testing "should pass custom executor through and not own it"
    (let [call-args (atom nil)]
      (with-open [custom (Executors/newVirtualThreadPerTaskExecutor)]
        (with-redefs [hc/build-http-client (fn [args] (reset! call-args args))]
          (let [client (test-client {::vistarmedia/http-opts {:executor custom}})]
            (is (identical? custom (:executor @call-args)))
            (is (nil? (::vistarmedia/executor client)))))))))

(deftest close-client-test
  (testing "should shut down own executor"
    (let [client (test-client)]
      (vistarmedia/close-client client)
      (is (.isShutdown ^ExecutorService (::vistarmedia/executor client)))))
  (testing "should not shut down custom executor"
    (with-open [custom (Executors/newVirtualThreadPerTaskExecutor)]
      (let [client (test-client {::vistarmedia/http-opts {:executor custom}})]
        (vistarmedia/close-client client)
        (is (not (.isShutdown custom)))))))

(defn- mock-responses
  "Like `mock-request`, but returns `responses` one by one."
  [calls responses]
  (let [remaining (atom responses)]
    (fn [args]
      (swap! calls conj args)
      (let [[resp] @remaining]
        (swap! remaining rest)
        resp))))

(defn- recording-wrapper
  [calls label]
  (fn [thunk]
    (swap! calls conj label)
    (thunk)))

(deftest wrap-request-test
  (testing "should use the client-level wrapper"
    (let [wrapped (atom [])
          calls   (atom [])]
      (with-redefs [hc/request (mock-responses calls [{:status 200 :body {:ok true}}])]
        (is (= {:ok true}
               (vistarmedia/invoke
                (test-client {::vistarmedia/wrap-request (recording-wrapper wrapped :global)})
                {::vistarmedia/op ::vistarmedia/advertisers-list})))
        (is (= [:global] @wrapped)))))
  (testing "per-call wrapper should replace the global one for the op, but not for login"
    (let [wrapped (atom [])
          calls   (atom [])]
      (with-redefs [hc/request (mock-responses calls [{:status 403 :body {}}   ; op
                                                      {:status 200 :body {}}   ; login
                                                      {:status 200 :body {:ok true}}])] ; retry
        (is (= {:ok true}
               (vistarmedia/invoke
                (test-client {::vistarmedia/wrap-request (recording-wrapper wrapped :global)})
                {::vistarmedia/op           ::vistarmedia/advertisers-list
                 ::vistarmedia/wrap-request (recording-wrapper wrapped :local)})))
        (is (= [:local :global :local] @wrapped))
        (is (match? [{:url #"/dsp/v1/advertisers$"}
                     {:url #"/session$"}
                     {:url #"/dsp/v1/advertisers$"}]
                    @calls)))))
  (testing "wrapper should receive the raw response before status checks"
    (let [seen  (atom nil)
          calls (atom [])]
      (with-redefs [hc/request (mock-responses calls [{:status 404 :body {:message "nope"}}])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Operation failed"
                              (vistarmedia/invoke
                               (test-client)
                               {::vistarmedia/op           ::vistarmedia/advertiser-get
                                ::vistarmedia/payload      {:id "1"}
                                ::vistarmedia/wrap-request (fn [thunk] (reset! seen (thunk)))})))
        (is (= 404 (:status @seen)))))))

(deftest invoke-test
  (testing "should throw on unknown operation"
    (let [calls (atom [])]
      (with-redefs [hc/request (mock-request calls nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unsupported operation"
                              (vistarmedia/invoke (test-client)
                                                  {::vistarmedia/op ::unsupported})))
        (is (= [] @calls)))))
  (testing "should try to re-login on 403"
    (let [calls    (atom [])
          response {:status 403 :body {}}]
      (with-redefs [hc/request (mock-request calls response)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Authorization failed"
                              (vistarmedia/invoke
                               (test-client)
                               {::vistarmedia/op ::vistarmedia/advertisers-list})))
        (is (match? [{:url "https://platform-api.vistarmedia.com/api/dsp/v1/advertisers"}
                     {:url "https://platform-api.vistarmedia.com/api/session"}]
                    @calls)))))
  (testing "should throw on missing path parameter"
    (let [calls    (atom [])
          response {:status 200 :body {}}]
      (with-redefs [hc/request (mock-request calls response)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Missing parameter"
                              (vistarmedia/invoke
                               (test-client)
                               {::vistarmedia/op ::vistarmedia/advertiser-get})))
        (is (= [] @calls)))))
  (testing "should pass only query-params for GET requests"
    (let [calls    (atom [])
          response {:status 200 :body {:some "data"}}
          payload  {:query-params {:filter "name:adgoji"}
                    :body         {:name "adgoji"}
                    :id           "123456"}]
      (with-redefs [hc/request (mock-request calls response)]
        (is (= {:some "data"}
               (vistarmedia/invoke (test-client)
                                   {::vistarmedia/op      ::vistarmedia/advertiser-get
                                    ::vistarmedia/payload payload})))
        (is (match? [{:query-params (:query-params payload)}]
                    @calls))
        (is (every? (every-pred (comp nil? :body) (comp nil? :id)) @calls)))))
  (testing "should pass only body for non-GET requests"
    (let [calls    (atom [])
          response {:status 204}
          payload  {:query-params {:filter "name:adgoji"}
                    :body         {:name "adgoji"}}]
      (with-redefs [hc/request (mock-request calls response)]
        (is (nil? (vistarmedia/invoke (test-client)
                                      {::vistarmedia/op      ::vistarmedia/campaigns-create
                                       ::vistarmedia/payload payload})))
        (is (match? [{:body (:body payload)}] @calls))
        (is (every? (comp nil? :query-params) @calls))))))
