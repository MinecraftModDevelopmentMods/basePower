"""Independent checks on recorded native progression evidence, not a recipe planner."""
import argparse
from collections import Counter, defaultdict
import io
import base64
import json
from pathlib import Path
import nbtlib
from rig_transport import digest, write_json


def require(value, reason):
    if not value: raise AssertionError(reason)


def hammer_witness(stock, uses, crafted, durability):
    tools=defaultdict(list)
    for encoded in stock:
        item=nbtlib.File.parse(io.BytesIO(base64.b64decode(encoded,validate=True)))
        identifier=str(item["id"])+"@0"
        if identifier in durability:
            require(isinstance(item["Damage"],nbtlib.Short) and isinstance(item["Count"],nbtlib.Byte),"Untyped tool damage/count")
            tools[identifier].extend([int(item["Damage"])]*int(item["Count"]))
    for identifier, count in crafted.items():
        capacity=durability[identifier]+1
        broken, damage=divmod(uses[identifier],capacity)
        expected=[0]*(count-broken)
        require(bool(expected) or damage==0,"Missing replacement hammer")
        if damage: expected[0]=damage
        require(sorted(tools[identifier])==sorted(expected),"Recorded native hammer damage disagrees with actual crush count")


def audit_case(case):
    phases=case["phaseReports"]
    require(case["status"]=="PASS" and phases["resume"]["status"]=="PASS" and phases["inspect"]["status"]=="PASS","Incomplete native case")
    first=phases["first"]["actions"];later=phases["continue"]["actions"]
    require(len(first)+len(later)==case["actions"],"Action coverage mismatch")
    uses=Counter();crushes=Counter();crafted=Counter();boundaries=[];trades=0
    durability=case["economy"]["toolDurability"]
    offers={row["id"]:row for row in case["economy"]["trades"]}
    for receipts in (first,later):
        for row in receipts:
            require(row["status"]=="PASS","Failed native action")
            if row["kind"]=="craft" and row["output"] in durability:
                delta=row["after"].get(row["output"],0)-row["before"].get(row["output"],0)
                require(delta>0,"No real hammer crafted");crafted[row["output"]]+=delta
            if row["kind"]=="crush":
                # Tool selection is retained in the pinned plan; native receipts expose its actual stock/damage.
                candidates=[tool for tool in durability if row["before"].get(tool,0)>0]
                require(len(candidates)==1,"Ambiguous tool witness; extend the independent auditor")
                crushes[candidates[0]]+=1
            if row["kind"]=="trade":
                trades+=1;uses[row["offer"]]+=1
                require(row["usesAfter"]==uses[row["offer"]]<=offers[row["offer"]]["maxUses"],"Merchant uses reset, duplicated or exceeded native offer stock")
        boundaries.append((dict(crushes),dict(crafted)))
    hammer_witness(phases["first"]["stockTyped"],Counter(boundaries[0][0]),Counter(boundaries[0][1]),durability)
    hammer_witness(phases["continue"]["stockTyped"],crushes,crafted,durability)
    require(phases["first"]["stockTyped"]==phases["continue"]["resumedStockTyped"],"Cold tool/item state changed")
    require(phases["resume"]["stockTyped"]==phases["inspect"]["stockTyped"],"Final cold item state changed")
    roots=set(phases["first"]["initialStock"])
    obtained=roots|{row["output"] for row in first+later}|{"baseelectric:petrolplastic_ingot@0"}
    gaps=[]
    for item,row in case["coverage"].items():
        if row["status"] in ("NATIVE_OBTAINED_FINITE_STOCK","LIVE_PRODUCED"):
            require(item in obtained,"Registry item was credited without any production or resource witness")
        elif row["status"]=="NO_FINITE_ROUTE":gaps.append(item)
        else:require(row["status"]=="EXCLUDED" and bool(row.get("reason")),"Unclassified target")
    require(set(gaps)<={"baseelectric:li_nugget@0"},"New progression gap cannot be hidden as a known unused item")
    return dict(status="PASS",actions=case["actions"],trades=trades,hammerCrushes=sum(crushes.values()),
                merchantOffersUsed=len(uses),registryTargets=len(case["coverage"]),unprovenItems=gaps,
                maximumSteamTemperature=phases["resume"]["maximumSteamFurnaceTemperature"])


def audit(report):
    require(report["status"]=="PASS" and report["protocolUnchanged"] is True and report["manifestUnchanged"] is True,"Unsealed or failed native run")
    expected={mode+suffix for mode in ("normal","tech_progression","apocalyptic") for suffix in ("-rubber","-no-rubber")}
    require(set(report["cases"])==expected,"All six mode/configuration cases required")
    result={name:audit_case(case) for name,case in report["cases"].items()}
    for name in ("apocalyptic-rubber","apocalyptic-no-rubber"):
        require(result[name]["trades"]>0,"Apocalyptic bootstrap has no paid native trading evidence")
        economy=report["cases"][name]["economy"]
        require(economy["physicalChestsSampled"]==448 and economy["nonemptyPhysicalChests"]>0,"Missing actual-chest positive control")
    return dict(status="PASS",cases=result,scope="Recorded native hammer damage, trade stock, coverage and restart checks")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt",type=Path);parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    result=audit(json.loads(args.receipt.read_text(encoding="utf-8")))
    result.update(receipt=str(args.receipt.resolve()),receiptSha256=digest(args.receipt),auditorSha256=digest(Path(__file__)))
    write_json(args.output,result);print(json.dumps(result,indent=2))
