(ns brokerage.store
  "SSoT for the ISCO-08 3311 independent securities brokerage
  practice actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section; README's 'Robotics premise' — a secure document-
  handling and archival robot performs trade-confirmation printing,
  disclosure packet assembly and physical archival under this
  advisor/governor pair, which never dispatches hardware itself and
  never executes a trade above a client's registered order-size
  limit). Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client  — a registered investor/institutional client (:client-id,
              :name)
    account — a registered brokerage account {:account-id :client-id
              :name :max-order-size number :suitability-reviewed?
              boolean}. `:max-order-size` is the registered order-size
              ceiling a proposed order must not exceed — executing an
              order beyond the client's registered order-size limit is
              unauthorized trading, not active management.
              `:suitability-reviewed?` records whether a suitability
              review has been completed for this account — accepting
              an order without a completed suitability review is
              unsuitable execution, not efficient service.
    record  — a committed operating record (an executed order) —
              written ONLY via commit-record!.
    ledger  — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (account [s account-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-account! [s a])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (account [_ account-id] (get-in @a [:accounts account-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-account! [s acct]
    (swap! a assoc-in [:accounts (:account-id acct)] acct) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :accounts {} :records [] :ledger []}
                                   seed)))))
