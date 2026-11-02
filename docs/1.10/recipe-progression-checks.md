# Recipe Progression Checks

The individual-recipe audit and the survival-progression test answer different questions. A recipe can match perfectly while requiring a component that a player cannot obtain. Neither test substitutes for the operating-machine suite or manual gameplay checks.

## What The New Test Does

The runner starts with a finite account of harvested resources: wood, crops, vanilla minerals and the registered copper, tin, zinc, lead, nickel, silver, lithium and sulfur ores. It does not start with ingots, tools, batteries, machine parts or stored energy. Resource gathering and mining time are outside this test.

It plans a route through the registered recipes, then replays the chosen route inside a packaged Forge 1.10.2 server:

- Crafting uses the native recipe manager and `SlotCrafting`. The selected ingredient alternatives must really match; a missing ingredient and a bedrock substitute must fail.
- Every craft debits its ingredients. Returned buckets are checked against a separate, explicit rule: the lead-acid and nickel-hydride recipes must each return one empty bucket.
- Vanilla smelting uses a previously crafted furnace and real coal. Bounded native furnace updates accelerate preparation; these are not wall-clock or natural-tick timing tests. Unused heat is discarded rather than credited as free energy.
- Crushing uses a crafted hammer on a real dropped item, collects its real products, and retains tool damage. A crusher-registry lookup alone cannot satisfy this step.
- Ore Dictionary alternatives are selected from obtainable materials, not simply the first registered entry. A proposed alternative is credited only after native replay succeeds.
- Apocalyptic routes may buy parts from the registered villager offers. Native merchant transactions debit currency and advance the offer's use count. No free restocking is assumed. Encountering villagers and levelling their careers are not simulated.
- Actual chest inventories are sampled in Apocalyptic mode, with nonempty vanilla chests as the positive control. Generated Additional Loot Tables configuration is not treated as proof of loot integration.

The runner stops the server after half the crafting steps. On the next process it restores the saved stock, including tool state and merchant offer uses, and finishes preparation without reseeding resources.

## The Starter Plant

The plant is assembled from the items made by that progression. Its terrain and bounded natural fluid sources are declared fixtures, not crafted machine components.

```text
1,000 mB crude oil -> coal-fired still -> 500 mB refined oil
                                             |
water stream -> hydroelectric generator -> plastic refinery -> 5 plastic ingots

water bucket + coal -> steam boiler -> steam pipe -> steam furnace + coal -> 1 iron ingot
```

Buckets collect actual source blocks. Container and machine fluid handlers transfer the resulting packets. The still consumes coal; the refinery receives only naturally generated hydroelectric power. No creative source, stored-energy injection, progress seeding or direct machine-update call is used in this plant.

The steam furnace also consumes its own coal. Merely finishing a coal-fired smelt would not prove that the steam connection worked: the test additionally requires received steam and a temperature above 800, beyond this furnace's coal-only heating limit.

After 120 natural ticks, the server saves and shuts down completely. Before any resumed machine tick, the next process compares exact block states and typed tile NBT with the checkpoint. It then finishes the work, checking fluid and product quantities. A final process restart checks the completed plant and retained products again.

Typed item NBT is checked as well as item counts. Resetting hammer damage, losing a container tag or reseeding a merchant offer is not an acceptable restart result.

## Matrix And Evidence

Run Normal, Tech Progression and Apocalyptic with `plastic_equals_rubber` both enabled and disabled. Each combination has four independent server processes: initial preparation, continued preparation/plant start, mid-cycle restart, and final cold inspection.

From `test-rig/migration`, using an exact pinned manifest and a new output directory:

```powershell
& <python> native110_survival_progression.py `
  --target-manifest <pinned-inputs.json> `
  --java8 <java8-bin/java.exe> `
  --output <new-disposable-output-directory>
```

The manifest pins the server and every jar by SHA-256. The runner archives its protocol sources, records every stock debit and credit, checks them independently, and refuses to reuse an output directory. Do not replace a failed receipt with a later passing run.

The coverage ledger includes every registered item, including block items. Creative sources, raw world fluids and hidden helper blocks have explicit exclusions. An unproven item remains visible as a gap; it is not silently removed to make the result green.

## Known Limits

The candidate's qualification results are recorded in [Current Candidate Checks](base-qualification.md).

The final matrix passed all six configurations and the independent stock/tool/trading audit. The overall progression ledger deliberately retains a `PARTIAL` completeness label because the unused lithium nugget below has no demonstrated producer.

The lithium nugget is registered in the original Electric Advantage release as well as this candidate, but the tested pack has no producer or recipe consumer for it. Examination of the original `2.2.0` jar finds its three lithium-nugget identifiers only in the item-registration class. That supports treating it as an unused legacy item, not a missing prerequisite for lithium batteries. It remains a reported progression gap; this test does not add a recipe or remove its identity.

Additional Loot Tables is not installed in the pinned pack. The test can demonstrate the trading route without it, but cannot claim that optional loot integration works when absent. No conclusion about loot rarity follows from sampling a bounded set of chest seeds.

Offers with missing currency or non-positive prices are listed as uncredited rather than used as free prerequisites. This is not a repair or qualification of third-party villager-trade balance. Optional RF/Reborn converter progression is outside this pack's recipe matrix.

Minecraft 1.10.2 has neither the later recipe book nor ingredient-triggered recipe advancements. Those require separate tests in later ports. This test also does not cover player-facing crafting interaction, shift-crafting, villager availability, mining balance or every third-party ingredient provider.
