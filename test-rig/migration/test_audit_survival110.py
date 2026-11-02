"""Synthetic negative controls for the independent evidence audit only."""
import base64
from copy import deepcopy
import io
import unittest
import nbtlib
from audit_survival110 import audit_case, audit

TOOL="basemetals:iron_crackhammer@0"


def tool(damage):
    tag=nbtlib.File({"id":nbtlib.String(TOOL.split('@')[0]),"Count":nbtlib.Byte(1),"Damage":nbtlib.Short(damage)})
    buffer=io.BytesIO();tag.write(buffer)
    return [base64.b64encode(buffer.getvalue()).decode("ascii")]


def fixture():
    def row(kind,output,**extra):return dict(status="PASS",kind=kind,output=output,**extra)
    first=[row("craft",TOOL,before={},after={TOOL:1}),row("crush","powder",before={TOOL:1}),
           row("trade","gear",offer="test-offer",usesAfter=1)]
    second=[row("crush","powder",before={TOOL:1}),row("trade","gear",offer="test-offer",usesAfter=2)]
    return dict(status="PASS",actions=5,economy=dict(toolDurability={TOOL:100},trades=[dict(id="test-offer",maxUses=7)]),
                coverage={TOOL:dict(status="NATIVE_OBTAINED_FINITE_STOCK")},phaseReports={
                    "first":dict(actions=first,initialStock={},stockTyped=tool(1)),
                    "continue":dict(actions=second,resumedStockTyped=tool(1),stockTyped=tool(2)),
                    "resume":dict(status="PASS",stockTyped=tool(2),maximumSteamFurnaceTemperature=1200),
                    "inspect":dict(status="PASS",stockTyped=tool(2))})


class IndependentAuditTests(unittest.TestCase):
    def test_valid_typed_wear_and_cross_restart_offer_uses(self):
        result=audit_case(fixture());self.assertEqual(2,result["trades"]);self.assertEqual(2,result["hammerCrushes"])

    def test_tool_damage_reset_fails_even_when_cold_counts_agree(self):
        data=fixture();data["phaseReports"]["first"]["stockTyped"]=tool(0)
        data["phaseReports"]["continue"]["resumedStockTyped"]=tool(0)
        with self.assertRaisesRegex(AssertionError,"damage"):audit_case(data)

    def test_free_merchant_restock_after_restart_is_rejected(self):
        data=fixture();data["phaseReports"]["continue"]["actions"][-1]["usesAfter"]=1
        with self.assertRaisesRegex(AssertionError,"Merchant"):audit_case(data)

    def test_unwitnessed_component_cannot_be_marked_available(self):
        data=fixture();data["coverage"]["free-machine"]=dict(status="NATIVE_OBTAINED_FINITE_STOCK")
        with self.assertRaisesRegex(AssertionError,"credited"):audit_case(data)

    def test_missing_action_or_changed_final_state_is_rejected(self):
        data=fixture();data["phaseReports"]["first"]["actions"].pop()
        with self.assertRaisesRegex(AssertionError,"coverage"):audit_case(data)
        data=fixture();data["phaseReports"]["inspect"]["stockTyped"]=tool(0)
        with self.assertRaisesRegex(AssertionError,"Final cold"):audit_case(data)

    def test_new_unreachable_item_cannot_hide_in_historical_gap(self):
        data=fixture();data["coverage"]["needed-machine"]=dict(status="NO_FINITE_ROUTE")
        with self.assertRaisesRegex(AssertionError,"gap"):audit_case(data)

    def test_incomplete_matrix_cannot_qualify(self):
        with self.assertRaisesRegex(AssertionError,"six"):audit(dict(status="PASS",protocolUnchanged=True,manifestUnchanged=True,cases={}))


if __name__=="__main__":unittest.main()
