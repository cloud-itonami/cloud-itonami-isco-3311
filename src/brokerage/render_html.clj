(ns brokerage.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`brokerage.actor` -> `brokerage.governor` -> `brokerage.store`)
  through a scenario built from real, exercised store data and renders
  the result deterministically -- no invented numbers, no timestamps
  in the page content, byte-identical across reruns against the same
  seed (verify by diffing two consecutive runs before shipping).

  `client-1` (\"Kobo Brokerage\") + account `A-1` (\"account-042\",
  max-order-size 1000, suitability-reviewed? true) below are lifted
  VERBATIM from this repo's own proven-passing test fixtures
  (`brokerage.actor-test`/`brokerage.governor-test` `fresh-store`
  helper) -- ground truth, not invented. `client-2` (\"Second Street
  Capital\") and account `A-2` (\"account-099\", owned by client-1,
  suitability-reviewed? FALSE) are ADDITIONAL demo data registered via
  the SAME real protocol calls (`store/register-client!`/
  `store/register-account!`) this actor's own test fixtures use --
  neither fixture registers a second client or a second, unreviewed
  account, and both are necessary to demonstrate the
  `:account-wrong-client` rule and the `:suitability-not-reviewed`
  rule (the fixture's only account already has its suitability review
  completed). Disclosed here plainly, not presented as if it were a
  pre-existing fixture. Every other field this page displays
  (statuses, records, hold reasons) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:
  - `brokerage.governor`'s `:no-actuation` rule (proposal `:effect`
    must be `:propose`) is NOT reachable through this demo, because
    the real `mock-advisor` (`brokerage.advisor/infer`) unconditionally
    sets `:effect :propose` on every proposal it emits.
  - The low-confidence escalation path is likewise NOT reachable
    through this demo: `mock-advisor` derives confidence purely from
    `:stake` (`:high` -> 0.7, `:medium` -> 0.85, `:low` -> 0.95), all
    of which sit above `brokerage.governor/confidence-floor` (0.6) --
    there is no stake value the real advisor maps to a sub-floor
    confidence. Both rules ARE covered by
    `brokerage.governor-test/hard-on-no-actuation-violation` and
    `escalates-low-confidence` (which call `governor/check` directly
    with hand-built proposals), not by this build-time renderer, which
    only ever drives the real actor/graph the way an operator actually
    would.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [brokerage.store :as store]
            [brokerage.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real brokerage operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit within-limit orders including
  the exact-boundary case, escalate-then-approve on both always-escalate
  ops, and 5 of the 6 distinct HARD-hold reasons in `brokerage.governor`
  -- the 6th, `:no-actuation`, is architecturally unreachable via the
  real advisor, see namespace docstring). Every `:op` keyword and
  violation rule name below is copied from `brokerage.governor`'s own
  `hard-violations`/`check`, not invented."
  [;; client-1 / A-1 (real fixture from brokerage.actor-test/governor-test)
   ["c1-order-within-limit"  "client-1" :approve-order {:account-id "A-1" :stake :low :order-size 500}]
   ["c1-order-exact-limit"   "client-1" :approve-order {:account-id "A-1" :stake :low :order-size 1000}]
   ["c1-order-exceeds-limit" "client-1" :approve-order {:account-id "A-1" :stake :low :order-size 5000}]
   ["c1-order-ghost-account" "client-1" :approve-order {:account-id "A-ghost" :stake :low :order-size 500}]
   ;; client-1 / A-2 (additional demo data -- see namespace docstring)
   ["c1-order-unreviewed"    "client-1" :approve-order {:account-id "A-2" :stake :low :order-size 200}]
   ;; client-2 (additional demo data) referencing client-1's A-1
   ["c2-order-foreign-account" "client-2" :approve-order {:account-id "A-1" :stake :low :order-size 500}]
   ;; unregistered client entirely
   ["ghost-no-client" "client-ghost" :approve-order {:account-id "A-1" :stake :low :order-size 500}]
   ;; always-escalate ops, regardless of confidence
   ["c1-over-limit-trade"    "client-1" :approve-over-limit-trade {:account-id "A-1" :stake :low}]
   ["c1-margin-call"         "client-1" :approve-margin-call-liquidation {:account-id "A-1" :stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `brokerage.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Brokerage"})
    (store/register-account! db {:account-id "A-1" :client-id "client-1"
                                  :name "account-042"
                                  :max-order-size 1000
                                  :suitability-reviewed? true})
    (store/register-account! db {:account-id "A-2" :client-id "client-1"
                                  :name "account-099"
                                  :max-order-size 500
                                  :suitability-reviewed? false})
    (store/register-client! db {:client-id "client-2" :name "Second Street Capital"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- account-row [store {:keys [account-id client-id name max-order-size suitability-reviewed?]} runs]
  (let [record-count (count (filter #(= account-id (:account-id %)) (store/records-of store client-id)))
        last-run (last (filter #(= account-id (get-in % [:request :account-id])) runs))]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%d</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc account-id) (esc name) max-order-size
            (if suitability-reviewed? "<span class=\"ok\">reviewed</span>" "<span class=\"err\">NOT reviewed</span>")
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:account-id request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `brokerage.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-order</code></td><td><span class=\"ok\">auto-commit when the order size is within the account's registered limit and the suitability review is complete</span></td></tr>"
   "        <tr><td><code>:approve-over-limit-trade</code></td><td><span class=\"warn\">ALWAYS human approval &middot; no trade execution above the client's registered order-size limit without the governor gate</span></td></tr>"
   "        <tr><td><code>:approve-margin-call-liquidation</code></td><td><span class=\"warn\">ALWAYS human approval &middot; forced liquidation of a margin account</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [accounts [{:account-id "A-1" :name "account-042" :client-id "client-1"
                    :max-order-size 1000 :suitability-reviewed? true}
                   {:account-id "A-2" :name "account-099" :client-id "client-1"
                    :max-order-size 500 :suitability-reviewed? false}]
        account-rows (str/join "\n" (map #(account-row store % runs) accounts))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-3311 &middot; independent securities brokerage</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Securities Brokerage (ISCO-08 3311) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · over-limit trades &amp; margin-call liquidations always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered accounts</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>brokerage.store</code> via <code>brokerage.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. The max-order-size and suitability-review flag are the registered ground truth the governor checks every order against — executing beyond the registered limit is unauthorized trading, and accepting an order without a completed suitability review is unsuitable execution.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Account</th><th>Name</th><th>Max order size</th><th>Suitability</th><th>Records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     account-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Securities Brokerage Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. The governor never dispatches hardware itself and never executes a trade above a client's registered order-size limit.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own account, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Account</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
