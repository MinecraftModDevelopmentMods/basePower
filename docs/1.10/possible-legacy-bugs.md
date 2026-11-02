# Inspector's Register of Possible Legacy Bugs

> **Inspection status:** This register preserves both historical findings and their dispositions. Reproduce open findings in copied worlds; repaired entries retain their original evidence and named regression checks.

[Handbook index](README.md) | [Commissioning cards](commissioning-and-fault-finding.md) | [Technical ledger](technical-ledger.md)

## Triage order

| ID | Component | Risk | Evidence state |
| --- | --- | --- | --- |
| `SA-110-001` | Steam pump tank persistence | High: collected fluid disappears after restart | Repaired in `2.2.2.110021`; automated restart regression passes |
| `EA-110-001` | Automated assembler recursive recipe handling | High if reproduced: server stall/time-out | Not reproduced in packaged-current; legacy-profile check remains |
| `EA-110-004` | Hydroelectric generator tile lifecycle | High: turbine produces one pulse, then loses its generator | Repaired in `2.2.2.110021`; direct-battery restart regression passes |
| `SA-110-002` | Geothermal temperature persistence | Medium: boiler restarts cold | Repaired in `2.2.2.110021`; automated restart regression passes |
| `EA-110-003` | Hydroelectric generator GUI registration and output | Medium: GUI is unavailable; dormant meter would overdraw | Repaired in `2.2.2.110021`; server checks pass, manual GUI check pending |
| `PA-110-001` | Refined-oil contact effect | None: original release behaviour confirmed | Closed; historical wording uncertainty |
| `EA-110-002` | Growth controller soil capacity | None: whole-block refill hysteresis works coherently | Closed by runtime characterization |
| `SA-110-003` | Steam crusher comparator output | Low: automation signal cannot distinguish input quantity | Repaired in `2.2.2.110021`; automated signal regression passes |
| `PA-110-002` | Forge fluid capability bypasses machine routing | High: raw oil enters the still's output tank and processing stops | Repaired in the local basePower `2.4.4.110021` candidate; focused and packaged-machine regressions pass |

## SA-110-001: steam pump reads a different tank key

- **Component:** `steamadvantage:steam_pump`.
- **Intended behaviour:** A pump's collected fluid remains in its internal tank across save, chunk unload, and complete Minecraft restart. This is coherent with the pump GUI, fluid output role, and normal tile-entity persistence.
- **Implemented behaviour:** `SteamPumpTileEntity.writeToNBT` serializes the tank under `Tank`; `readFromNBT` checks for `Tank` but then retrieves `TankOut`.
- **Source:** Steam Advantage `src/main/java/com/mcmoddev/steamadvantage/machines/SteamPumpTileEntity.java:277`, `:288`, `:289` at baseline `c182530`.
- **Likely player impact:** Buffered fluid appears empty after a complete restart; pumping may not resume correctly until state/topology changes.
- **Test hypothesis:** A non-empty internal tank reloads empty because the reader obtains an absent compound even though canonical `Tank` exists.
- **Runtime confirmation:** On 2026-09-06, packaged SteamAdvantage `2.2.1.110021` under Forge `12.18.3.2511` saved `Tank:{FluidName:"water",Amount:1000}` before a clean full-process restart and loaded as `Tank:{Empty:""}` afterward. The empty state was independently confirmed in the pump GUI.
- **Repair:** SteamAdvantage `2.2.2.110021` reads canonical `Tank`, falling back to legacy `TankOut`, and continues to write only `Tank`.
- **Regression result:** The isolated S-06 witness retained exactly 1,000 mB of water after a clean server shutdown and full process restart in the packaged-current profile.
- **Test control:** An initial restart appeared to retain 1,000 mB because saved steam energy let the pump immediately collect replacement water. Remove or isolate the source pool after recording a non-empty pump, save again, and only then restart.
- **Required layout:** [Card S-06](commissioning-and-fault-finding.md#card-s-06-steam-pump), bounded water pool that can be isolated, measured output tank, exact pre-save internal quantity.
- **Versions to check:** Maintained 1.10.2 first; original 1.10.2 release jars; maintained and historical 1.12.2 readers for compatible fallback requirements.

## EA-110-001: automated assembler can hang on metal blocks

- **Component:** `electricadvantage:electric_fabricator`.
- **Intended behaviour:** Recursively deconstruct a bounded target recipe only to the configured recursion limit, consume available ingredients, and complete or stop without blocking the server.
- **Implemented behaviour:** The main mod class contains an explicit TODO: metal blocks can cause the Automated Assembler to hang and time out players. Recursive recipe expansion may encounter reversible ingot/block recipe cycles or excessive search.
- **Source:** Electric Advantage `src/main/java/com/mcmoddev/electricadvantage/ElectricAdvantage.java:39` plus `machines/ElectricFabricatorTileEntity.java` and recipe-deconstructor classes at baseline `0f8bf34`.
- **Likely player impact:** Integrated-server or dedicated-server tick stall, disconnect, or apparent world freeze.
- **Test hypothesis:** A reversible storage-block target produces cyclic or explosively growing recursive work despite the configured depth limit.
- **Runtime characterization:** On 2026-09-07, packaged ElectricAdvantage `2.2.1.110021` with BaseMetals `2.5.0-beta4`, OreSpawn `3.2.2`, and the default recursion limit of 5 completed a crafting-table control and returned normally from empty-input searches for a vanilla iron block, BaseMetals copper and starsteel blocks, and the deeper Electric Fabricator recipe. Each failed search reached the machine's `WAITING` state, server commands remained responsive, no tick-stall or timeout warning appeared, and the connected player independently observed no client freeze or other issue.
- **Disposition:** Not reproduced in the current packaged profile. Keep the entry open for the original release and `packaged-legacy` dependency profile because the source TODO may describe a recipe graph supplied by an older BaseMetals/OreSpawn combination or another historical mod.
- **Required layout:** [Card E-08](commissioning-and-fault-finding.md#card-e-08-assembler-timeout-hypothesis), copied world, bounded inventory, recursion 1 before higher values, server-tick monitoring.
- **Versions to check:** 1.10.2 and 1.12.2; test several ore-dictionary metals because recipe alternatives can alter the graph.

## SA-110-002: geothermal boiler reads a misspelled temperature key

- **Component:** `steamadvantage:steam_boiler_geothermal`.
- **Intended behaviour:** Stored temperature remains after reload, allowing the boiler to continue from its saved thermal state.
- **Implemented behaviour:** The writer stores `Temperature`; the reader tests `Temperature` but reads `Tempoerature`. The returned float is therefore normally zero.
- **Source:** Steam Advantage `src/main/java/com/mcmoddev/steamadvantage/machines/GeothermalBoilerTileEntity.java:210`, `:228`, `:229` at baseline `c182530`.
- **Likely player impact:** An apparently sound boiler becomes cold after restart, delaying steam and confusing supply diagnostics.
- **Test hypothesis:** Water and steam remain but the temperature gauge resets to zero after full restart.
- **Runtime confirmation:** On 2026-09-06, packaged SteamAdvantage `2.2.1.110021` under Forge `12.18.3.2511` saved `Temperature:1347.5896f` before a clean full-process restart. The same boiler loaded with no `Temperature` value and its GUI temperature gauge was independently confirmed cold.
- **Repair:** SteamAdvantage `2.2.2.110021` reads canonical `Temperature`, falling back to legacy `Tempoerature`, and continues to write only `Temperature`.
- **Regression result:** S-01 loads a controlled hot state through both historical and canonical spellings, saves it canonically, and retains a value above zero after a clean server shutdown and full process restart in the packaged-current profile.
- **Test control:** Remove the adjacent heat source immediately before the pre-restart checkpoint. Otherwise the boiler can begin reheating as soon as its chunk loads and partially conceal the reset.
- **Required layout:** [Card S-01](commissioning-and-fault-finding.md#card-s-01-boiler-calibration), isolated boiler/tank, removable adjacent heat, pre/post NBT and GUI readings.
- **Versions to check:** Maintained 1.10.2, original 1.10.2, and both 1.12.2 key spellings so a repair can read old misspelled data if any build wrote it.

## PA-110-001: closed - original refined oil also causes nausea

- **Component:** `poweradvantage:refined_oil` block.
- **Historical wording:** DrCyano's refined-oil page describes contact as poisonous, but does not name Minecraft's `poison` potion effect.
- **Implemented behaviour:** Maintained code applies the `nausea` potion effect for 200 ticks (10 seconds). Crude oil applies Slowness III for the same duration.
- **Original-release evidence:** The compiled callback in `PowerAdvantage_1.10.2-2.3.0.jar` also constructs the `nausea` potion effect for 200 ticks. The inspected fixture has SHA-256 `47CD509C1141095BFE1C8C7DD689C2ECD3F99C89195E34A88CC9E0B5FC12413E`.
- **Source:** Original release `cyano.poweradvantage.init.Blocks.lambda$init$0`; maintained `src/main/java/zone/moddev/mc/poweradvantage/init/Blocks.java:117-122`; historical Power Advantage fluid documentation.
- **Disposition:** Closed as not a maintained-code regression. Interpret the historical word "poisonous" as descriptive wording rather than proof that the Minecraft Poison effect was intended. Do not change gameplay on this evidence.
- **Optional characterization:** A contained survival-contact test may still document the visible Nausea effect, but it is not a defect test.

## EA-110-003: hydroelectric GUI is unregistered and its dormant meter value is out of range

- **Component:** `electricadvantage:hydroelectric_generator`.
- **Intended behaviour:** The hydroelectric generator is a `GUIBlock` and uses the same generator display design as the photovoltaic and steam-powered generators. Right-click should open that display, whose progress value is normalized to the 0..1 range.
- **Implemented behaviour:** `init.GUI` registers `PowerGeneratorGUI` for the steam-powered and photovoltaic generators but omits the hydroelectric generator. Its inherited right-click handler therefore finds a null GUI owner and returns without opening anything. If that registration alone is repaired, `HydroelectricGeneratorTileEntity.getPowerOutput()` returns 15 while active; the progress renderer expects approximately 0..1 and would calculate a 481-pixel strip instead of its normal 33-pixel maximum.
- **Source:** Electric Advantage `init/GUI.java:16-18`, `machines/HydroelectricGeneratorTileEntity.java:14`, `:54-59`, `entities/HydroturbineEntity.java:86`, `gui/PowerGeneratorGUI.java:40-41`, and `gui/GUIHelper.java:32-36` at baseline `0f8bf34`; Power Advantage `cyano/poweradvantage/api/GUIBlock.java:206-212`.
- **Likely player impact:** Players cannot open the hydroelectric generator GUI. Repairing only the missing registration would expose a severely overdrawn output bar.
- **Test hypothesis:** An active generator produces 4 electricity per tick but right-click opens no GUI. After both code repairs, right-click opens a normal-width full-output bar and measured generation remains 4 per tick.
- **Runtime confirmation:** On 2026-09-07, packaged ElectricAdvantage `2.2.1.110021` under Forge `12.18.3.2511` reached active metadata `10` and stored 500 electricity in a controlled horizontal water channel, but right-click opened no interface. The original `ElectricAdvantage_1.10.2-2.2.0.jar` also omits the hydroelectric `setGuiID` call and returns `15.0f` from `getPowerOutput()`; the inspected fixture has SHA-256 `29A6EFA25B9519B32A2FCA853F3D8CC89E18ADBFD44A7BC4C4D0EC5F4C6A551B`.
- **Repair:** ElectricAdvantage `2.2.2.110021` registers the hydroelectric `PowerGeneratorGUI`, reports an active GUI value of `1.0` without changing its 4-electricity-per-tick generation, and clamps all GUI progress values to `0..1` defensively.
- **Regression result:** E-01 proves the turbine remains active and charges its directly connected battery after a full server restart. Opening and visually checking the hydroelectric GUI remains a manual client acceptance step.
- **Required layout:** [Card E-01](commissioning-and-fault-finding.md#card-e-01-generators), bounded battery load, horizontal water channel through the turbine, active-state and energy checkpoint, right-click check, and timed energy measurement.
- **Versions to check:** Maintained/original 1.10.2 and 1.12.2; inspect the progress-bar helper before choosing normalization semantics.

## EA-110-004: generator state change invalidates the hydro turbine parent

- **Component:** `electricadvantage:hydroelectric_generator` and its managed hydroturbine entity.
- **Intended behaviour:** A valid spinning turbine continuously supplies its generator, and the generator may switch its active blockstate without replacing the associated tile entity. A connected battery should therefore continue charging after save, shutdown, and full-process restart.
- **Implemented behaviour:** `ElectricGeneratorTileEntity.setActive` manually removed, validated, and re-added its own tile entity around a same-block state change even though `shouldRefresh` already preserves the tile for that transition. The turbine retained the now-invalid old tile as `parent`; on its next server tick it killed itself, so the generator received only one 4-electricity pulse.
- **Source:** Electric Advantage `src/main/java/com/mcmoddev/electricadvantage/machines/ElectricGeneratorTileEntity.java:64-76` and `entities/HydroturbineEntity.java:77-89` before the `2.2.2.110021` repair.
- **Likely player impact:** A turbine can appear briefly active or spinning while useful generation stops, leaving directly connected storage empty and making the fault look like a conduit or GUI problem.
- **Test hypothesis:** A valid E-01 water channel with a directly adjacent battery records only a single generator pulse and no battery charge before repair. Removing the redundant tile replacement should leave the turbine parent valid and allow sustained transfer through a complete restart.
- **Runtime confirmation:** The exact E-01 witness with ElectricAdvantage `2.2.1.110021` reached one 4-electricity pulse and left its directly connected battery at zero. With `2.2.2.110021`, the post-restart generator remained active at metadata `10` and the same battery reached `672.0` electricity after the settling interval.
- **Repair:** ElectricAdvantage `2.2.2.110021` changes only the blockstate property and relies on `shouldRefresh` to preserve the existing tile entity.
- **Regression result:** E-01 now verifies active hydro state and positive energy in the directly connected battery after a clean shutdown and full server-process restart.
- **Required layout:** [Card E-01](commissioning-and-fault-finding.md#card-e-01-generators), horizontal water channel, managed turbine, generator, and one directly adjacent battery array containing a rechargeable battery.
- **Versions to check:** Inspect and characterize the 1.12.2 generator state-change implementation before applying an equivalent repair.

## EA-110-002: closed - soil capacity uses whole-block refill hysteresis

- **Component:** `electricadvantage:growth_chamber_controller`.
- **Coherent behaviour:** Each dirt contributes 1,000 soil and is accepted only when that whole contribution fits below the 1,500 upper bound. This leaves a waiting dirt in the slot while soil is above or equal to 500, then refills toward 1,500 once active consumption takes soil below 500 without discarding a partial block's value.
- **Implemented behaviour:** Automatic inventory consumption requires `MAX_SOIL - soil > SOIL_PER_BLOCK`. At exactly 500 soil the strict comparison delays refill until the next active consumption tick takes soil below 500; this is a small hysteresis boundary rather than unreachable capacity during operation.
- **Source:** Electric Advantage `src/main/java/com/mcmoddev/electricadvantage/machines/GrowthChamberControllerTileEntity.java:21-22`, `:50-51`, `:150` at baseline `0f8bf34`.
- **Runtime characterization:** On 2026-09-07, packaged ElectricAdvantage `2.2.1.110021` under Forge `12.18.3.2511` consumed one dirt into `soil:1000.0f` while isolated from electricity and held a second dirt in input slot 0. After electricity was connected, soil fell to `825.0f` while the dirt still waited. Continued greenhouse load took soil below the refill threshold, the dirt disappeared, and a later checkpoint showed an empty input with `soil:837.5f` after further consumption. The controller GUI exposes no numeric soil-capacity claim.
- **Disposition:** Closed as coherent buffer behaviour. Do not change the capacity or consume dirt at 1,000; doing so would waste half of the second dirt's declared contribution or require a new partial-consumption rule.
- **Required layout:** [Card E-05](commissioning-and-fault-finding.md#card-e-05-growth-chambers), controlled soil consumption, two dirt blocks, water and electricity metered separately.
- **Optional parity check:** Confirm 1.12.2 retains the same whole-block refill cycle; no 1.10.2 gameplay repair is indicated.

## SA-110-003: steam crusher comparator saturates on any input

- **Component:** `steamadvantage:steam_crusher`.
- **Intended behaviour:** Comparator output should conventionally scale from 1 to 15 with the fullness of the monitored input.
- **Implemented behaviour:** The expression is effectively `15 * stackSize` before clamping because maximum stack size is multiplied and divided by itself. Any non-empty ordinary stack therefore clamps to 15.
- **Source:** Steam Advantage `src/main/java/com/mcmoddev/steamadvantage/machines/RockCrusherTileEntity.java:200-202` at baseline `c182530`.
- **Likely player impact:** Redstone automation cannot distinguish one item from a full input stack.
- **Test hypothesis:** Comparator reads 15 for input counts 1, 32, and 64, and 0 only when empty.
- **Runtime confirmation:** On 2026-09-06, packaged SteamAdvantage `2.2.1.110021` under Forge `12.18.3.2511` produced comparator output `15` from exactly one cobblestone in input slot 0. The signal remained powered at the far end of a 13-block redstone line. A one-cobblestone vanilla chest control produced output `1` using the same comparator orientation.
- **Repair:** SteamAdvantage `2.2.2.110021` uses vanilla-style proportional scaling: empty `0`, then `1..15` according to input fullness.
- **Regression result:** The isolated unpowered S-04 witness reports comparator level `1` for one cobblestone before and after a full server restart in the packaged-current profile.
- **Test control:** Allow at least two redstone ticks after changing the input before reading the comparator tile's `OutputSignal`; an immediate same-tick read can report the previous value.
- **Required layout:** Steam crusher, comparator and dust/readout line, valid input item at four stack levels; [Card S-04](commissioning-and-fault-finding.md#card-s-04-blast-furnace-and-crusher).
- **Versions to check:** Original and maintained 1.10.2, then 1.12.2.

## PA-110-002: Forge fluid capability bypasses machine routing

- **Component:** The shared `TileEntitySimpleFluidMachine` capability bridge, particularly `basepower:still`.
- **Intended behaviour:** A Forge fluid capability must use the machine's fluid handler, including its input/output routing and side restrictions. A still accepts crude oil into its input reserve and exposes refined oil as output.
- **Implemented behaviour before repair:** `getCapability` returned the raw output `FluidTank`. This bypassed the still's overridden `fill` method and any overridden handler restrictions.
- **Runtime confirmation:** The new finite-material progression test collected a real crude-oil bucket and transferred its 1,000 mB through the native capability. The saved still held `Tank:{FluidName:"crude_oil",Amount:1000}` while `InputTank` remained empty; its coal was untouched and no distillation occurred. The full restart preserved this wrong routing rather than repairing it.
- **Focused controls:** Three of four initial capability tests failed: overridden input routing, side/fluid restrictions, and input capacity/properties. The output-only drain control already passed.
- **Repair:** Delegate the capability through Forge's `FluidHandlerWrapper(this, facing)`. No recipe, processing ratio, registry identity or NBT key changes.
- **Regression:** All four focused tests pass. The packaged starter plant now turns exactly 1,000 mB crude into 500 mB refined oil and five plastic ingots, with real fuel and hydroelectric generation. The rebuilt Power jar also passes all 188 operating cases and 188 cold-state comparisons. See [recipe progression checks](recipe-progression-checks.md) for the wider matrix and its limits.
- **Source:** `src/main/java/cyano/poweradvantage/api/simple/TileEntitySimpleFluidMachine.java`, method `getCapability`; `StillTileEntity.fill` and `getTankInfo` define the required routing.
- **Versions to check:** Maintained 1.10.2 confirmed and repaired locally. Other Minecraft lines and the original release's capability behaviour have not been requalified by this repair.

## Reviewed differences not presently classified as bugs

| Observation | Disposition |
| --- | --- |
| Historical Electric Drill range says "about 60"; code uses 64. | Documentation rounding; use the exact code value 64 for tests. |
| Historical growth documentation permits any number of chambers. | Contiguous typed-machine conduction supports expansion; practical supply limits are expected, not a contradiction. |
| Drain page illustration emphasises fluid above, while update notes mention adjacent handlers. | Later intent and code agree: source search is above; Forge handlers can be on any face. |
| PCB Printer and Geothermal Generator have language strings but no registrations. | Incomplete or removed features; documented as unavailable, not malfunctioning equipment. |
| Musket load uses both use-completion and release events. | Interaction needs runtime characterization, but historical material is not precise enough to declare a defect. |
| Steam gauges can be low while a machine works or conducts to another machine. | Local-buffer display plus staggered network updates can explain it. Treat as a bug only if work, server state, and client state demonstrably diverge. |

## Closing an entry

An entry is ready to close only when the recorded rig has been run against the named build, the result is reproducible, intended behaviour is resolved from source/history/component purpose, save compatibility is considered, and a separate code change has its own regression test. Do not remove the old entry; mark its disposition and link the fixing commit so future maintainers can understand the saved-world concern.
