(ns brokerage.governor
  "SecuritiesBrokerageGovernor — the independent safety/traceability
  layer named in this repository's README/business-model.md, gating
  every order an advisor may propose for an account. The governor
  never dispatches hardware itself and never executes a trade above a
  client's registered order-size limit. Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Task twist: a
  proposed order size is an arithmetic ceiling against the account's
  registered order-size limit, and an order cannot be accepted until
  the account's suitability review has been completed.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance    — the investor/institutional client must
                              be registered.
    2. no-actuation         — proposal :effect must be :propose (the
                              governor never dispatches hardware and
                              never executes a trade above the
                              client's registered order-size limit; it
                              only gates what the advisor may
                              execute).
    3. account basis        — an order proposal must cite a
                              REGISTERED account belonging to this
                              client.
    4. order-size ceiling   — the proposed order size must not exceed
                              the account's registered
                              `:max-order-size` (executing an order
                              beyond the client's registered order-
                              size limit is unauthorized trading, not
                              active management).
    5. suitability reviewed — the account must have
                              `:suitability-reviewed?` true before any
                              order can be accepted (accepting an
                              order without a completed suitability
                              review is unsuitable execution, not
                              efficient service).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off per
  business-model.md's Trust Controls — these are :high/
  :safety-critical regardless of confidence):
    6. :op :approve-over-limit-trade (no trade execution above the
                              client's registered order-size limit
                              without the governor gate).
    7. :op :approve-margin-call-liquidation (forced liquidation of a
                              margin account always requires human
                              sign-off).
    8. low confidence (< `confidence-floor`)."
  (:require [brokerage.store :as store]))

(def confidence-floor 0.6)

(def ^:private always-escalate-ops #{:approve-over-limit-trade
                                     :approve-margin-call-liquidation})

(defn- hard-violations [{:keys [request proposal]} client-record a]
  (let [{:keys [op order-size]} proposal
        order? (= :approve-order op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は登録上限超過の取引執行を直接実行しない）"})

      (and order? (nil? a))
      (conj {:rule :unknown-account :detail "未登録 account への注文提案は不可"})

      (and order? a (not= (:client-id a) (:client-id request)))
      (conj {:rule :account-wrong-client :detail "account が別 client のもの"})

      (and order? a (number? order-size) (> order-size (:max-order-size a)))
      (conj {:rule :order-exceeds-limit
             :detail (str "注文数量 " order-size " > 登録済み上限 "
                          (:max-order-size a) "（登録上限を超える執行は無許可取引であってアクティブ運用ではない）")})

      (and order? a (not (:suitability-reviewed? a)))
      (conj {:rule :suitability-not-reviewed
             :detail "適合性レビュー未完了の account への注文受付は不適合執行であって効率的サービスではない"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `brokerage.store/Store`. Pure — never mutates
  the store, never executes a trade above the registered order-size
  limit."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        a (some->> (:account-id proposal) (store/account store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record a)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
