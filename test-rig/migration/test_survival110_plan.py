"""Planner/receipt negative controls. These tests do not claim gameplay proof."""
from copy import deepcopy
import unittest
from survival110_plan import Planner, Unobtainable
from native110_survival_progression import validate_replay


def recipe(output, inputs, providers=None, count=1):
    return dict(id="craft/0",status="PASS",output=output,inputs=inputs,
                providers=providers or [[x] for x in inputs],count=count,width=3)


def doc(rows=()):
    return dict(crafting=list(rows),smelting=[],crushing=[],economy=dict(trades=[],toolDurability={}))


class FinitePlannerTests(unittest.TestCase):
    def test_selects_obtainable_alternative_not_first_registry_entry(self):
        p=Planner(doc([recipe("gear",["diamond_rod"],[["diamond_rod","iron_rod"]])]),{"iron_rod":1})
        p.ensure("gear")
        self.assertEqual(["iron_rod"],p.actions[0]["inputs"])
        self.assertEqual(0,p.stock["iron_rod"])

    def test_duplicate_ingredient_requires_real_quantity(self):
        p=Planner(doc([recipe("gear",["iron","iron"])]),{"iron":1})
        with self.assertRaises(Unobtainable):p.ensure("gear")
        self.assertEqual(1,p.stock["iron"])
        self.assertEqual([],p.actions)

    def test_cycles_do_not_create_starting_components(self):
        p=Planner(doc([recipe("gear",["chip"]),recipe("chip",["gear"])]),{})
        with self.assertRaises(Unobtainable):p.ensure("gear")
        self.assertEqual([],p.actions)

    def test_failed_provider_rolls_back_partial_prerequisites(self):
        p=Planner(doc([recipe("gear",["a","missing"]),recipe("gear",["b"])]),{"a":1,"b":1})
        p.ensure("gear")
        self.assertEqual(1,p.stock["a"])
        self.assertEqual(0,p.stock["b"])
        self.assertEqual(1,len(p.actions))

    def test_water_bucket_is_not_free_and_returns_empty_bucket(self):
        d=doc([recipe("battery",["minecraft:water_bucket@0"])])
        p=Planner(d,{"minecraft:bucket@0":1})
        p.ensure("battery")
        self.assertEqual(["water","craft"],[a["kind"] for a in p.actions])
        self.assertEqual(1,p.stock["minecraft:bucket@0"])
        self.assertEqual(0,p.stock["minecraft:water_bucket@0"])
        with self.assertRaises(Unobtainable):Planner(d,{}).ensure("battery")

    def test_smelting_requires_furnace_and_fuel(self):
        d=doc();d["smelting"]=[dict(input="ore",output="ingot",count=1)]
        for budget in ({"ore":1},{"ore":1,"minecraft:furnace@0":1}):
            with self.assertRaises(Unobtainable):Planner(d,budget).ensure("ingot")
        p=Planner(d,{"ore":1,"minecraft:furnace@0":1,"minecraft:coal@0":1});p.ensure("ingot")
        self.assertEqual(0,p.stock["minecraft:coal@0"])

    def test_trade_requires_money_and_does_not_assume_restock(self):
        d=doc();d["economy"]["trades"]=[dict(id="offer",input="emerald",price=2,output="gear",count=1,maxUses=1)]
        with self.assertRaises(Unobtainable):Planner(d,{"emerald":1},True).ensure("gear")
        p=Planner(d,{"emerald":4},True);p.ensure("gear")
        with self.assertRaises(Unobtainable):p.ensure("gear",2)
        self.assertEqual(2,p.stock["emerald"])
        with self.assertRaises(Unobtainable):Planner(d,{"emerald":4}).ensure("gear")

    def test_missing_currency_and_zero_price_are_not_free_intermediates(self):
        for row in (dict(id="bad",price=0,output="gear",count=1,maxUses=7),
                    dict(id="bad",input="emerald",price=0,output="gear",count=1,maxUses=7)):
            d=doc();d["economy"]["trades"]=[row];p=Planner(d,{"emerald":4},True)
            with self.assertRaises(Unobtainable):p.ensure("gear")
            self.assertEqual(1,len(p.uncredited_trades))

    def test_no_diamond_rod_can_be_credited_from_an_unreplayed_provider_list(self):
        p=Planner(doc([recipe("gear",["diamond_rod"],[["diamond_rod","iron_rod"]])]),{"iron_rod":1})
        p.ensure("gear")
        self.assertEqual(0,p.stock["diamond_rod"])
        self.assertNotIn("diamond_rod",p.actions[0]["inputs"])

    def test_machine_recipe_cannot_consume_the_only_workbench(self):
        d=doc([recipe("minecraft:crafting_table@0",["wood"]),recipe("machine",["minecraft:crafting_table@0"])])
        p=Planner(d,{"wood":2,"minecraft:crafting_table@0":1})
        p.ensure("machine")
        self.assertEqual(1,p.stock["minecraft:crafting_table@0"])
        self.assertEqual(1,p.stock["wood"])


class ReceiptTests(unittest.TestCase):
    def fixture(self):
        action=dict(kind="craft",output="gear",count=1,inputs=["iron"],width=1,recipe="craft/0")
        p=dict(initial={"iron":1},actions=[action],expectedStock={"gear":1})
        row=dict(index=0,kind="craft",status="PASS",output="gear",before={"iron":1},after={"gear":1},
                 selectedInputs=["iron"],nativeConsumption="SlotCrafting",negativeControlsRejected=2,returnedEmptyBuckets=0)
        first=dict(actions=[row],stock={"gear":1},stockTyped=["exact-nbt-test-fixture"])
        continued=dict(actions=[],craftedStock={"gear":1},resumedStock={"gear":1},resumedStockTyped=["exact-nbt-test-fixture"])
        return p,first,continued

    def test_valid_accounting(self):validate_replay(*self.fixture())

    def test_rejects_faked_receipts(self):
        for field,value in (("index",1),("nativeConsumption","LOOKUP_ONLY"),("negativeControlsRejected",0),
                            ("after",{"gear":2}),("before",{"iron":9}),("selectedInputs",["diamond_rod"]),
                            ("returnedEmptyBuckets",1),("output","chip")):
            p,first,continued=self.fixture();first["actions"][0][field]=value
            with self.subTest(field=field),self.assertRaises(AssertionError):validate_replay(p,first,continued)

    def test_missing_or_duplicate_step_fails(self):
        for rows in ([],[0,0]):
            p,first,continued=self.fixture();first["actions"]=[deepcopy(first["actions"][i]) for i in rows]
            with self.assertRaises(AssertionError):validate_replay(p,first,continued)

    def test_restart_may_not_reseed_inventory(self):
        p,first,continued=self.fixture();continued["resumedStock"]={"gear":1,"iron":99}
        with self.assertRaisesRegex(AssertionError,"reset"):validate_replay(p,first,continued)

    def test_tool_damage_reset_fails_even_if_every_item_count_is_unchanged(self):
        p,first,continued=self.fixture();continued["resumedStockTyped"]=["same-tool-reset-damage"]
        with self.assertRaisesRegex(AssertionError,"Typed"):validate_replay(p,first,continued)


if __name__=="__main__":unittest.main()
