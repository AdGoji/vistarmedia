(ns user
  (:require
   [com.adgoji.vistarmedia :as vistarmedia]))

(defn- client
  []
  (vistarmedia/new-client
   {::vistarmedia/credential
    {:email    (System/getenv "TEST_EMAIL")
     :password (System/getenv "TEST_PASSWORD")}}))

(comment
  (vistarmedia/invoke (client)
                      {::vistarmedia/op ::vistarmedia/advertisers-list
                       ::vistarmedia/payload
                       {:query-params {:search "name:adg"}}})

  )
