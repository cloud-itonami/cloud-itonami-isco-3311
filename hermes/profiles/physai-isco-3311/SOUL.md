# physai-isco-3311 — 証券・金融ディーラー／ブローカー（ISCO 3311）の文書取扱い・保管ロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-3311`、ISCO 3311 証券・金融ディーラー／ブローカー）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: セキュアな文書取扱い・保管ロボットが取引確認書の印刷・開示書類一式の組立て・物理保管を行う。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:disclosure-packets-to-mail-tray` | manipulator | 組み上がった開示書類の束をプリンタ排紙トレーから発送トレーへ持ち上げる | 肩関節ピークトルク | 50 N·m（estimate） |
| `:record-cabinet-fire-wall` | thermal | ロボットが保管する耐火書庫キャビネットの断熱壁に 1 時間の炉加熱（壁面 927 °C 固定、冷却過程なし） | 庫内側壁面温度 | 177 °C（UL 72 Class 350、出典あり） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:test`（`test/brokerage/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **書類の束**: 肩トルクは 0.5 kg で 20.97 N·m、2.5 kg（コピー用紙 1 連相当）で 31.55 N·m、5 kg で 44.86 N·m、7.5 kg で 58.21 N·m（限界超過）。
   限界 50 N·m に達する積荷は **5.962 kg**。関節仕事は位置エネルギー変化と一致（5 kg で 44.64 J）。
2. **耐火キャビネット**: 1 時間後の庫内側壁面は壁厚 2 cm で 508.0 °C、3 cm で 366.0 °C、4 cm で 237.6 °C（いずれも限界超過）、6 cm で 84.4 °C、8 cm で 32.7 °C。
   177 °C を守れる壁厚は **4.6 cm 以上**（この断熱材の仮定で）。2 cm 壁は 572 s で 177 °C に達する。
   solver は石膏系断熱材の結晶水の吸熱・炉の昇温曲線・冷却過程を持たない（壁面を最初から 927 °C に固定）ので、この値は保守側。
3. **estimate のままの値**: 肩トルク上限 50 N·m（協働アームの仕様書で置き換える）、断熱材の熱物性（k 0.12・密度 900・比熱 1000）、
   炉温を 927 °C 一定と置いたこと（ASTM E119 標準加熱曲線の 1 時間値として扱った近似）、庫内側の熱伝達率 5 W/m²K。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-3311 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-3311 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
