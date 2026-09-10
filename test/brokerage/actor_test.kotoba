(ns brokerage.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [brokerage.actor :as actor]
            [brokerage.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Brokerage"})
    (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                 :name "account-042"
                                 :max-order-size 1000
                                 :suitability-reviewed? true})
    st))

(deftest commits-a-within-limit-reviewed-order
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-order :stake :low
                 :account-id "A-1" :order-size 500}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-over-limit-order
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-order :stake :low
                 :account-id "A-1" :order-size 5000}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-over-limit-trade-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-over-limit-trade :stake :low
                 :account-id "A-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
