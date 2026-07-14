(ns brokerage.advisor
  "Brokerage Advisor — the advisor named in this repository's README,
  proposing a brokerage operation (execute an order, approve an over-
  limit trade, approve a margin-call liquidation) from a client
  account, order instruction and suitability profile. Swappable
  mock/llm; the advisor ONLY proposes — `brokerage.governor` checks
  the order-size ceiling and suitability review independently and
  always escalates over-limit-trade and margin-call-liquidation
  decisions. Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-order|:approve-over-limit-trade|:approve-margin-call-liquidation
               :effect :propose :account-id str :order-size number
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake account-id order-size] :as request}]
  {:op op
   :effect :propose
   :account-id account-id
   :order-size order-size
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a securities-brokerage advisor. Given a request, propose an
   :op, the :account-id and :order-size, an honest :confidence and a
   :stake. Never propose an order size beyond the account's registered
   order-size limit, or an order for an account whose suitability
   review is incomplete — the governor checks both against the
   registered account record. Over-limit trades and margin-call
   liquidations always require human sign-off regardless of
   confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
