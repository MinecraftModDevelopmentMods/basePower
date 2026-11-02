"""Native finite-material crafting/trading and naturally ticking starter-plant checks."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import socket
import traceback

from rig_transport import digest, write_json
from native110 import output_path, pinned_profile, require_disjoint
from native110_code import INVENTORY, Server, verify_code
from native110_lifecycle import prepare, read_json, require_native_boot, verify_staged
from native110_recipe_progression import MODES, mode_checks, canonical
from survival110_plan import plan


def validate_replay(proposed, first, continued):
    rows = first["actions"] + continued["actions"]
    if len(rows) != len(proposed["actions"]): raise AssertionError("Missing/repeated finite replay step")
    stock = Counter(proposed["initial"])
    for index, (action, receipt) in enumerate(zip(proposed["actions"], rows)):
        if (receipt.get("index") != index or receipt.get("status") != "PASS" or receipt.get("kind") != action["kind"]
                or receipt.get("output") != action["output"] or receipt.get("before") != +stock):
            raise AssertionError("Wrong replay step or pre-debit stock at " + str(index))
        debits = Counter()
        kind = action["kind"]
        if kind == "craft":
            if receipt.get("nativeConsumption") != "SlotCrafting" or receipt.get("negativeControlsRejected") != 2:
                raise AssertionError("Missing real craft consumption or negative controls")
            if receipt.get("selectedInputs") != action["inputs"]: raise AssertionError("Substituted ingredient route")
            debits.update(x for x in action["inputs"] if x)
            returned = action.get("emptyBucketsReturned", 0)
            if receipt.get("returnedEmptyBuckets") != returned: raise AssertionError("Missing/duplicated empty bucket")
        elif kind == "smelt":
            if not 200 <= receipt.get("nativeUpdates", 0) <= 220 or receipt.get("coalDebited") != 1:
                raise AssertionError("Registered smelting function is not fuelled processing evidence")
            debits.update([action["input"], action["fuel"]])
        elif kind == "crush":
            if receipt.get("durabilityDebited") != 1 or receipt.get("nativeInteraction") != "Item.onItemUse + EntityItem outputs":
                raise AssertionError("Crushing lookup is not a real hammer interaction")
            debits.update([action["input"]])
            if receipt.get("toolBroke") is not action["toolBreaks"]: raise AssertionError("Wrong tool lifetime")
            if action["toolBreaks"]: debits[action["tool"]] += 1
        elif kind == "trade":
            if receipt.get("nativeConsumption") != "SlotMerchantResult" or receipt.get("offer") != action["trade"]["id"]:
                raise AssertionError("Registered offer is not a paid native transaction")
            debits[action["trade"]["input"]] += action["trade"]["price"]
            if action["trade"].get("secondInput"): debits[action["trade"]["secondInput"]] += action["trade"]["secondPrice"]
        elif kind == "water": debits["minecraft:bucket@0"] += 1
        else: raise AssertionError("Unknown action")
        if any(stock[item] < count for item, count in debits.items()): raise AssertionError("Free intermediate item or overdraft")
        stock.subtract(debits)
        stock[action["output"]] += action["count"]
        if kind == "craft": stock["minecraft:bucket@0"] += action.get("emptyBucketsReturned", 0)
        if receipt.get("after") != +stock: raise AssertionError("Wrong native material balance at " + str(index))
    if +stock != proposed["expectedStock"] or continued.get("craftedStock") != +stock:
        raise AssertionError("Final finite stock disagrees with independent accounting")
    if first.get("stock") != continued.get("resumedStock"):
        raise AssertionError("Crafting stock reset across full process restart")
    if not first.get("stockTyped") or first["stockTyped"]!=continued.get("resumedStockTyped"):
        raise AssertionError("Typed item/tool state changed across full process restart")


def validate_phases(proposed, phases):
    for name, expected in (("first", "CRAFT_CHECKPOINT"), ("continue", "MACHINE_CHECKPOINT"), ("resume", "PASS"), ("inspect", "PASS")):
        row = phases[name]
        if (row.get("status") != expected or row.get("mode") != proposed["mode"]
                or row.get("rubber") is not proposed["rubber"] or row.get("minecraft") != "1.10.2"):
            raise AssertionError("Incomplete/wrong native phase " + name)
    validate_replay(proposed, phases["first"], phases["continue"])
    for phase in ("resume", "inspect"):
        if phases[phase].get("coldMachineState") != "EXACT_TYPED_NBT_AND_BLOCK_EQUAL":
            raise AssertionError("Machine state not checked cold before restart ticks")
    live = phases["resume"]
    if (live.get("crudeOilDebited"), live.get("refinedOilProducedAndConsumed"), live.get("plasticProduced"), live.get("steamSmeltProduced")) != (1000,500,5,1):
        raise AssertionError("Live fluid/product conservation or paid steam work failed")
    if not 620 <= live.get("naturalMachineTicks", 0) < 2200: raise AssertionError("No bounded natural machine operation")
    if live.get("steamReceived") is not True or live.get("maximumSteamFurnaceTemperature",0)<=800:
        raise AssertionError("Coal-only smelting is not proof of network steam heating")
    if phases["inspect"].get("stock") != live.get("stock"): raise AssertionError("Products lost or duplicated after final restart")
    if not live.get("stockTyped") or phases["inspect"].get("stockTyped")!=live["stockTyped"]:
        raise AssertionError("Final typed item state changed across full process restart")
    operation_stock=Counter(proposed["expectedStock"])
    for item in ("basepower:still@0","baseelectric:hydroelectric_generator@0","baseelectric:plastic_refinery@0",
                 "basesteam:steam_boiler_coal@0","basesteam:steam_furnace@0","basesteam:steam_pipe@0"):
        operation_stock[item]-=1
    operation_stock["minecraft:coal@0"]-=3
    operation_stock["minecraft:iron_ore@0"]-=1
    if any(v<0 for v in operation_stock.values()) or phases["continue"]["stock"] != +operation_stock:
        raise AssertionError("Starter plant did not debit exact crafted machinery, inputs and fuel")
    operation_stock["baseelectric:petrolplastic_ingot@0"]+=5
    if live["stock"] != +operation_stock: raise AssertionError("Live production invented or lost stock")
    water=1000*sum(action["kind"]=="water" for action in proposed["actions"])+2000
    if live.get("waterSourcesCollectedMb")!=water: raise AssertionError("Missing bucket/source-fluid conservation")


def run(args):
    root = output_path(args.output)
    manifest = args.target_manifest.resolve()
    target = pinned_profile(manifest, "target110")
    for path in (manifest, target[0], *target[2]): require_disjoint(root,path)
    code = read_json(manifest)["target110"]["serverCode"]
    verify_code(target[0],code)
    with socket.socket() as sock: sock.bind(("127.0.0.1",args.port))
    root.mkdir(parents=True)
    sources = [Path(__file__), *[Path(__file__).with_name(name) for name in
        ("survival110_plan.py", "native110_recipe_progression.py", "rig_transport.py", "native110.py", "native110_code.py", "native110_lifecycle.py", "native110_diagnostics.py")],
        *[Path(__file__).parents[1]/"src/main/java/zone/moddev/mc/advantageworks"/name for name in
          ("NativeSurvivalProgressionProbe.java", "NativeRecipeProgressionProbe.java", "NativeEntityHold.java", "NativeFluidPort.java")]]
    seals = {str(path):digest(path) for path in sources}
    for path in sources:
        dest=root/"protocol-sources"/path.name; dest.parent.mkdir(exist_ok=True); shutil.copy2(path,dest)
    report = dict(status="RUNNING", startedAtUtc=datetime.now(timezone.utc).isoformat(), manifest=str(manifest), manifestSha256=digest(manifest),
                  protocolSha256=seals, cases={},
                  scope="FINITE_HARVESTED_INPUTS_NATIVE_CRAFTS_HAMMER_TRADES_BUCKETS_AND_NATURAL_STARTER_PLANT",
                  limitations=["Resource harvesting, villager encounter and career leveling are not simulated",
                               "Vanilla furnaces use bounded native updates with real coal, not wall-clock ticks",
                               "Additional Loot Tables is not in the pinned pack; absent loot remains a reported integration gap",
                               "No 1.10 recipe book or ingredient advancements exist"])
    server=None
    try:
        for mode in ([args.mode] if args.mode else MODES):
            for rubber in ([args.rubber == "on"] if args.rubber else (True,False)):
                name=mode.lower()+("-rubber" if rubber else "-no-rubber")
                runtime=root/name
                launcher=prepare(runtime,target,[],args.port)
                verify_code(runtime,code);write_json(runtime/INVENTORY,code)
                for marker in ("advantage-legacy-recipes.allow","advantage-survival.allow"): (runtime/marker).touch()
                config=runtime/"config/basepower.cfg";config.parent.mkdir(exist_ok=True)
                config.write_text('options {\n S:recipe_mode='+mode+'\n B:plastic_equals_rubber='+str(rubber).lower()+'\n}\n',encoding="utf-8")
                phases={}; exports={}
                for phase in ("first","continue","resume","inspect"):
                    verify_staged(runtime,target)
                    server=Server(runtime,args.java8,launcher,phase)
                    if phase in ("first","inspect"):
                        server.send("advlegacyrecipes "+phase);server.wait("ADVLEGACYRECIPES COMPLETE "+phase,180)
                        doc=read_json(runtime/"advantage-works-results"/("legacy-recipes-"+phase+".json"))
                        mode_checks(doc,mode,rubber);exports[phase]=doc
                        if phase=="first":
                            proposed=plan(doc)
                            gaps={item for item,row in proposed["coverage"].items() if row["status"]=="NO_FINITE_ROUTE"}
                            if gaps-{"baseelectric:li_nugget@0"}: raise AssertionError("New unclassified progression blockers: "+str(sorted(gaps)))
                            write_json(runtime/"survival-plan.json",proposed)
                            plan_seal=digest(runtime/"survival-plan.json")
                            print(name+" PLAN actions="+str(len(proposed["actions"])),flush=True)
                    if digest(runtime/"survival-plan.json")!=plan_seal: raise AssertionError("Finite plan changed during progression")
                    server.send("advsurvival "+phase+" "+phase)
                    server.wait("ADVSURVIVAL COMPLETE "+phase,240)
                    phases[phase]=read_json(runtime/"advantage-works-results"/("survival-"+phase+".json"))
                    server.send("save-all flush");server.close();server=None
                    require_native_boot(runtime/("console-"+phase+".log"));verify_staged(runtime,target)
                    if phases[phase].get("status")=="FAIL": raise AssertionError(phases[phase].get("failure","Failed native phase"))
                if canonical(exports["first"])!=canonical(exports["inspect"]): raise AssertionError("Native recipes changed across restart")
                if exports["first"]["economy"]!=exports["inspect"]["economy"]: raise AssertionError("Native offers/loot/durability changed across restart")
                if digest(runtime/"survival-plan.json")!=plan_seal: raise AssertionError("Finite plan changed during progression")
                validate_phases(proposed,phases)
                coverage=proposed["coverage"]
                for target_item,row in coverage.items():
                    if row["status"]=="NATIVE_REPLAY_REQUIRED": row["status"]="NATIVE_OBTAINED_FINITE_STOCK"
                    elif row["status"]=="LIVE_MACHINE_REQUIRED": row["status"]="LIVE_PRODUCED"
                report["cases"][name]=dict(status="PASS",planSha256=digest(runtime/"survival-plan.json"),phaseReports=phases,
                                               actions=len(proposed["actions"]),coverage=coverage,
                                               uncreditedTrades=proposed["uncreditedTrades"],
                                               sampledNativeLoot=exports["first"]["sampledNativeLoot"],economy=exports["first"]["economy"])
                write_json(root/"survival-results-progress.json",report)
                print("SURVIVAL110 PASS "+name,flush=True)
        report["status"]="PASS"
        report["completeProgression"]="PARTIAL" if any(row["status"]=="NO_FINITE_ROUTE" for case in report["cases"].values() for row in case["coverage"].values()) else "QUALIFIED_WITHIN_DECLARED_SCOPE"
    except Exception as error:
        report.update(status="FAIL",failure=str(error),traceback=traceback.format_exc())
    finally:
        if server:
            try:server.close()
            except Exception as error:report.update(status="FAIL",shutdownError=str(error))
        report["manifestUnchanged"]=report["manifestSha256"]==digest(manifest)
        report["protocolUnchanged"]=seals=={str(path):digest(path) for path in sources}
        if not report["manifestUnchanged"] or not report["protocolUnchanged"]:report["status"]="FAIL"
        report["completedAtUtc"]=datetime.now(timezone.utc).isoformat()
        write_json(root/"native110-survival-results.json",report)
    print("SURVIVAL110 "+report["status"]+" "+report.get("failure",""),flush=True)
    return 1 if report["status"]=="FAIL" else 0


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-manifest",type=Path,required=True)
    parser.add_argument("--java8",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--port",type=int,default=25597)
    parser.add_argument("--mode",choices=MODES)
    parser.add_argument("--rubber",choices=("on","off"))
    raise SystemExit(run(parser.parse_args()))
