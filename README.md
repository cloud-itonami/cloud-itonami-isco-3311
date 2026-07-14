# cloud-itonami-isco-3311

Open Occupation Blueprint for **ISCO-08 3311**: Securities and Finance Dealers and Brokers.

This repository designs a forkable OSS business for an independent securities brokerage practice: a secure document-handling and archival robot manages trade confirmations and disclosures under a governor-gated actor, so the practice keeps its own brokerage records instead of renting a closed brokerage-platform SaaS.

**Maturity: `:implemented`.** `src/brokerage/` implements the
`SecuritiesBrokerageActor` as a `langgraph.graph/state-graph`
(`brokerage.actor`) wired to a `Brokerage Advisor` (`brokerage.advisor`)
and an independent `SecuritiesBrokerageGovernor` (`brokerage.governor`),
following the itonami actor pattern (ADR-2607011000): `:intake -> :advise
-> :govern -> :decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. 14 tests / 29 assertions
green (`clojure -M:test`). HARD invariants (always hold, never
overridable): client provenance, no-actuation (`:effect` must be
`:propose`), a registered account basis for any order proposal, the
proposed order size not exceeding the account's registered order-size
ceiling (executing an order beyond the client's registered limit is
unauthorized trading, not active management), and a completed
suitability review before any order can be accepted (accepting an
order without a completed suitability review is unsuitable execution,
not efficient service). Always-escalate ops (human sign-off regardless
of confidence, mapping this repo's Trust Controls in
[`docs/business-model.md`](docs/business-model.md)):
`:approve-over-limit-trade` and `:approve-margin-call-liquidation`.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a secure document-handling and archival robot performs trade-confirmation printing, disclosure packet assembly and physical archival under an actor that proposes
actions and an independent **Securities Brokerage Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
trade execution above the client's registered order-size limit) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
client account + order instruction + suitability profile
        |
        v
Brokerage Advisor -> Securities Brokerage Governor -> execute order/advise, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `3311`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
