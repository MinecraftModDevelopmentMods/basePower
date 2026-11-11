# Local Native61 Order Probe

This is a local, instrumented seventh-mod test. It is not a normal six-jar production qualification. No production jar is rewritten. Existing `native12111.py` / `native117_code.py` launch policy is unchanged; every export launch retains `DIAGNOSTIC_NOT_QUALIFIED` and `qualificationAllowed=false`.

## Build And Run

Use Java21 and the migration venv. `build.py --profile <six-jar-profile> --seed <sealed-seed> --java <java21> --output <new-build-dir>` runs independent javac using the existing Eclipse dependency classpath, not Gradle. It emits two separately pinned `AdvantageOrderProbe-1.0.0.121111.jar` variants and a build receipt. Never reuse an existing output directory.

`migration/native12111_order_probe.py` accepts the same sealed profile/seed/inventory/Java inputs, plus the sidecar build receipt/SHA, actual original110 source, independent legacy reference/receipt SHA, new output, and a starting port in 26520..26528. It runs conversion, cold restart and resumed operation in each observed order. The six production records are retained verbatim in each derived seven-mod profile.

## Guards And Proof

The config-only plugin changes precisely `mixinPriority` for the two existing child configs, before preparation. It pins the six jars, both configs, all thirteen declared production mixin classes, and native Mixin0.8.7 implementation classes. It rejects duplicate resources/declarations, prepared children, explicit class priorities, unexpected defaults, and unrelated priority changes. Its own config declares no target mixins. The mod requests WorldGenRegion with class initialization disabled solely to make its real woven bytecode available in fully generated old worlds.

The audit checks actual generated handler origins, priorities and full instruction fingerprints, observed HEAD/RETURN order, both native returns, cooperative noncancellation, inventory staging after both HEADs, and control-flow dominance including native exception edges. Both actual orders must be reverse permutations at every audited site and stable across three boots. Requests/labels alone cannot pass.

Only two expected-body transformations are permitted: the worldgen handlers' initial `ALOAD 0; CHECKCAST WorldGenRegion` receiver casts are elided before expected hashing, exactly as pinned Mixin0.8.7 `MixinTargetContext.transformTypeNode` specifies. Observed bodies are never filtered. The rest of each method participates in its hash.

Focused controls: `migration/test_native12111_order_probe.py`; existing launch-policy tests; `test/run_woven_controls.py` (eleven rejected derivative-class mutations); `test/run_guard_controls.py` (ten rejected synthetic-jar guard mutations). These helpers create only new synthetic artifacts, never modify child target jars. `test/retain_final_evidence.py` retains nine per-handler hashes and three raw javap views for each completed boot.

## C2 Receipt

Final actual result: `test-rig/build/native12111-final-c2-order-final/native12111-order-probe-results.json`, PASS, scope `INSTRUMENTED_ORDER_TEST_ONLY`, normal qualification forbidden. All source/reference/seed/input/six-production-jar custody flags are true.

Exact final sidecar build: `test-rig/build/native12111-order-probe-build-c2-d/build-receipt.json`. Six-boot raw exports and plugin pre/post proofs remain in the runtime `order-evidence` directories; readable disassembly and body hashes are in `test-rig/build/native12111-order-probe-final-evidence-c2`.

Earlier attempts a/b/c are preserved FAILs (missing unloaded target, strict receiver-cast comparison, then explicit review of native exception edges). They are not promoted to passes. See the verification handoff for actor fixture gaps and the independent normal six-jar C2 results.
