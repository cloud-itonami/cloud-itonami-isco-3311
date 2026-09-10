(ns brokerage.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [brokerage.store :as store]
            [brokerage.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Brokerage"})
    (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                 :name "account-042"
                                 :max-order-size 1000
                                 :suitability-reviewed? true})
    st))

(defn- order-op [size]
  {:op :approve-order :effect :propose :account-id "A-1"
   :order-size size :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-limit-and-reviewed
  (let [st (fresh-store)
        v (governor/check req {} (order-op 500) st)]
    (is (:ok? v))))

(deftest ok-at-exact-limit-boundary
  (testing "the order-size ceiling is inclusive"
    (let [st (fresh-store)
          v (governor/check req {} (order-op 1000) st)]
      (is (:ok? v)))))

(deftest hard-on-order-exceeds-limit
  (testing "executing an order beyond the client's registered order-size limit is unauthorized trading, not active management"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (order-op 5000) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :order-exceeds-limit (:rule %)) (:violations v))))))

(deftest hard-on-suitability-not-reviewed
  (testing "accepting an order without a completed suitability review is unsuitable execution, not efficient service"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "client-1" :name "Kobo Brokerage"})
      (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                   :name "account-042"
                                   :max-order-size 1000
                                   :suitability-reviewed? false})
      (let [v (governor/check req {} (assoc (order-op 500) :confidence 0.99) st)]
        (is (:hard? v))
        (is (some #(= :suitability-not-reviewed (:rule %)) (:violations v)))))))

(deftest hard-on-unknown-account
  (let [st (fresh-store)
        v (governor/check req {} (assoc (order-op 500) :account-id "A-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-account (:rule %)) (:violations v)))))

(deftest hard-on-foreign-account
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (order-op 500) st)]
      (is (:hard? v))
      (is (some #(= :account-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (order-op 500) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (order-op 500) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest always-escalates-over-limit-trade-even-at-high-confidence
  (testing "no trade execution above the client's registered order-size limit without the governor gate"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-over-limit-trade :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-margin-call-liquidation-even-at-high-confidence
  (testing "forced liquidation of a margin account always requires human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-margin-call-liquidation :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (order-op 500) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
