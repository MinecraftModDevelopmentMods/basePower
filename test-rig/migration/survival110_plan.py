"""Finite material planner. Proposed routes are NOT proof until native replay passes."""
from collections import Counter, defaultdict
from native110_recipe_progression import EXCLUSIONS


class Unobtainable(ValueError):
    pass


def concrete(token):
    return token.replace("@32767", "@0") if token else token


class Planner:
    def __init__(self, doc, stock, allow_trades=False):
        self.doc = doc
        self.stock = Counter(stock)
        self.initial = dict(stock)
        self.actions = []
        self.routes = defaultdict(list)
        self.tools = Counter()
        self.trade_uses = Counter()
        self.uncredited_trades = []
        self.reserved = {"minecraft:crafting_table@0": 1, "minecraft:furnace@0": 1}
        self.attempts = 0
        for row in doc["crafting"]:
            if row.get("status") == "PASS":
                self.routes[row["output"]].append(dict(kind="craft", recipe=row))
        for row in doc["smelting"]:
            self.routes[row["output"]].append(dict(kind="smelt", recipe=row))
        for row in doc["crushing"]:
            self.routes[row["output"]].append(dict(kind="crush", recipe=row))
        if allow_trades:
            for row in doc["economy"]["trades"]:
                if (not isinstance(row.get("input"),str) or type(row.get("price")) is not int or row["price"]<=0
                        or type(row.get("maxUses")) is not int or row["maxUses"]<=0):
                    self.uncredited_trades.append(dict(id=row["id"],output=row["output"],reason="Missing currency, non-positive price or invalid offer stock"))
                else:
                    self.routes[row["output"]].append(dict(kind="trade", recipe=row))
        self.routes["minecraft:water_bucket@0"].append(dict(kind="water", recipe=dict(count=1)))
        self.ranks = {item: 0 for item, count in stock.items() if count > 0}
        for _ in range(100):
            changed = False
            for item, routes in self.routes.items():
                rank = min((self.route_rank(route) for route in routes), default=1000000)
                if rank < self.ranks.get(item, 1000000):
                    self.ranks[item] = rank
                    changed = True
            if not changed: break

    def route_rank(self, route):
        row, kind = route["recipe"], route["kind"]
        rank = lambda item: self.ranks.get(concrete(item), 1000000)
        if kind == "craft":
            cells = row.get("providers", [[x] if x else [] for x in row["inputs"]])
            return 1 + max((min(map(rank, choices)) for choices in cells if choices), default=1000000)
        if kind == "smelt": return 1 + max(rank(row["input"]), rank("minecraft:furnace@0"), rank("minecraft:coal@0"))
        if kind == "crush": return 1 + max(rank(row["input"]), min(map(rank, row["tools"]), default=1000000))
        if kind == "trade": return 1 + max(rank(row["input"]), rank(row["secondInput"]) if row.get("secondInput") else 0)
        if kind == "water": return 1 + rank("minecraft:bucket@0")
        raise ValueError(kind)

    def snapshot(self):
        return self.stock.copy(), len(self.actions), self.tools.copy(), self.trade_uses.copy()

    def restore(self, snapshot):
        self.stock, action_count, self.tools, self.trade_uses = snapshot
        del self.actions[action_count:]

    def take(self, item, count=1, path=()):
        if count <= 0:
            raise ValueError("Non-positive debit")
        self.ensure(item, count + self.reserved.get(item, 0), path)
        self.stock[item] -= count

    def ensure(self, item, count=1, path=()):
        self.attempts += 1
        if self.attempts > 100000:
            raise RuntimeError("Planner search budget exhausted; do not claim unreachable")
        if self.stock[item] >= count:
            return
        if item not in self.ranks:
            raise Unobtainable("No resource-rooted route for " + item)
        if item in path or len(path) > 40:
            raise Unobtainable("Cyclic or excessively deep prerequisite: " + item)
        path = (*path, item)
        while self.stock[item] < count:
            success = False
            # Prefer an already available input, then short non-recycling paths.
            routes = sorted(self.routes[item], key=lambda r: self.cost(r))
            for route in routes:
                before = self.snapshot()
                try:
                    self.produce(item, route, path)
                    success = True
                    break
                except Unobtainable:
                    self.restore(before)
            if not success:
                raise Unobtainable("No finite route for " + item)

    def cost(self, route):
        row, kind = route["recipe"], route["kind"]
        if kind == "trade": return (self.route_rank(route), 0, 0)
        if kind == "water": return (self.route_rank(route), 0, 1)
        if kind in ("smelt", "crush"):
            return (self.route_rank(route), 0 if self.stock[row["input"]] else 2, 1 if kind == "smelt" else 2)
        cells = row.get("providers", [[x] if x else [] for x in row["inputs"]])
        return (self.route_rank(route), sum(not any(self.stock[concrete(x)] for x in choices) for choices in cells if choices), len(cells))

    def produce(self, item, route, path):
        row, kind = route["recipe"], route["kind"]
        action = dict(kind=kind, output=item, count=row["count"])
        if kind == "craft":
            selected = []
            for choices in row.get("providers", [[x] if x else [] for x in row["inputs"]]):
                if not choices:
                    selected.append(None)
                    continue
                candidate = None
                for choice in sorted(dict.fromkeys(map(concrete, choices)), key=lambda x: (not bool(self.stock[x]), self.ranks.get(x,1000000))):
                    before = self.snapshot()
                    try:
                        self.take(choice, path=path)
                        candidate = choice
                        break
                    except Unobtainable:
                        self.restore(before)
                if candidate is None:
                    raise Unobtainable("Missing ingredient for " + item)
                selected.append(candidate)
            action.update(recipe=row["id"], inputs=selected, width=row["width"])
            # Independent policy: a water-bucket ingredient returns exactly one EMPTY bucket.
            buckets = selected.count("minecraft:water_bucket@0")
            if buckets:
                self.stock["minecraft:bucket@0"] += buckets
                action["emptyBucketsReturned"] = buckets
        elif kind == "smelt":
            self.ensure("minecraft:furnace@0", path=path)
            self.take(row["input"], path=path)
            self.take("minecraft:coal@0", path=path)
            action.update(input=row["input"], fuel="minecraft:coal@0")
        elif kind == "crush":
            self.take(row["input"], path=path)
            tool = None
            for candidate in sorted(row["tools"], key=lambda x: (not bool(self.stock[x]), "iron_crackhammer" not in x)):
                before = self.snapshot()
                try:
                    if not self.tools[candidate]:
                        self.ensure(candidate, 1, path)
                        self.tools[candidate] += self.doc["economy"]["toolDurability"][candidate] + 1
                    tool = candidate
                    break
                except Unobtainable:
                    self.restore(before)
            if tool is None: raise Unobtainable("No obtainable crushing tool for " + item)
            self.tools[tool] -= 1
            if not self.tools[tool]: self.stock[tool] -= 1
            action.update(input=row["input"], tool=tool)
            action["toolBreaks"] = not bool(self.tools[tool])
        elif kind == "trade":
            if self.trade_uses[row["id"]] >= row["maxUses"]:
                raise Unobtainable("Offer exhausted; restocking is not assumed")
            self.take(row["input"], row["price"], path)
            if row.get("secondInput"): self.take(row["secondInput"], row["secondPrice"], path)
            self.trade_uses[row["id"]] += 1
            action.update(trade=row)
        elif kind == "water":
            self.take("minecraft:bucket@0", path=path)
            action["input"] = "minecraft:bucket@0"
        else: raise ValueError(kind)
        self.stock[item] += row["count"]
        self.actions.append(action)


def raw_budget(doc):
    # Harvested resources, not ingots, machine parts, tools, or free energy.
    names = ("log", "sapling", "cobblestone", "coal", "iron_ore", "gold_ore", "sand", "clay_ball",
             "gravel", "redstone", "diamond", "emerald", "obsidian", "potato", "wheat", "reeds",
             "gunpowder", "flint", "leather", "string", "feather", "quartz", "bone", "cactus", "dirt")
    stock = {"minecraft:"+name+"@0": 2048 for name in names}
    for ore in ("oreCopper", "oreTin", "oreZinc", "oreLead", "oreNickel", "oreSilver", "oreLithium", "oreSulfur"):
        for token in doc["ores"].get(ore, []):
            token = concrete(token)
            if token in doc["blockItems"]: stock[token] = 2048
    return stock


STARTER = ("minecraft:bucket@0", "basemetals:iron_crackhammer@0", "basepower:sprocket@0",
           "basepower:fluid_pipe@0", "basepower:still@0", "basesteam:steam_governor@0",
           "basesteam:steam_pipe@0", "basesteam:steam_boiler_coal@0", "basesteam:steam_furnace@0",
           "baseelectric:electric_conduit@0", "baseelectric:psu@0", "baseelectric:hydroelectric_generator@0",
           "baseelectric:plastic_refinery@0", "baseelectric:lead_acid_battery@0",
           "baseelectric:nickel_hydride_battery@0")


def plan(doc):
    planner = Planner(doc, raw_budget(doc), allow_trades=doc["mode"] == "APOCALYPTIC")
    planner.ensure("minecraft:crafting_table@0")
    for item in STARTER:
        planner.ensure(item)
    if doc["mode"] != "APOCALYPTIC": planner.ensure("baseelectric:photovoltaic_generator@0")
    coverage = {}
    for target in doc["targets"]:
        if target in EXCLUSIONS:
            coverage[target] = dict(status="EXCLUDED", reason=EXCLUSIONS[target])
        elif target == "baseelectric:petrolplastic_ingot@0":
            coverage[target] = dict(status="LIVE_MACHINE_REQUIRED")
        elif target == "baseelectric:photovoltaic_generator@0" and doc["mode"] == "APOCALYPTIC":
            coverage[target] = dict(status="EXCLUDED", reason="Intentional Apocalyptic restriction")
        else:
            before = planner.snapshot()
            try:
                planner.ensure(target)
                coverage[target] = dict(status="NATIVE_REPLAY_REQUIRED")
            except Unobtainable as error:
                planner.restore(before)
                coverage[target] = dict(status="NO_FINITE_ROUTE", reason=str(error))
    # Two water packets for the boiler/stream, plus an empty bucket for crude oil.
    planner.reserved.update({item: 1 for item in STARTER})
    for item in STARTER:
        planner.ensure(item)
    planner.ensure("minecraft:bucket@0", 1)
    return dict(schema=1, mode=doc["mode"], rubber=doc["rubber"], initial=planner.initial,
                actions=planner.actions, expectedStock={k:v for k,v in planner.stock.items() if v},
                checkpoints=list(STARTER), coverage=coverage,
                uncreditedTrades=planner.uncredited_trades,
                scope="FINITE_HARVESTED_RESOURCE_BUDGET; NATIVE_REPLAY_REQUIRED; NOT_MINING_OR_VILLAGER_LEVELING")
