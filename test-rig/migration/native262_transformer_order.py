"""Pinned Forge65 two-order process proof. Instrumented, never production qualification."""

import argparse
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tomllib
from types import SimpleNamespace
import traceback
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
import native12111_order_probe as prior
import native262_upgrade as upgrade

SCOPE = prior.SCOPE
MOD_ID, VERSION = prior.MOD_ID, "1.0.0.2602001"
MOD_IDS, MODES, VECTORS = upgrade.MOD_IDS, prior.MODES, prior.VECTORS
RIG = Path(__file__).resolve().parents[1]
TEMPLATES = RIG / "order-probe"
TAG = "Lnet/minecraft/nbt/CompoundTag;"
UPGRADE = "(" + TAG + "I" + TAG + "I)" + TAG
SELECTED = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess"
RAW_READ = "(Ljava/nio/file/Path;)" + TAG
TARGETS = ("net/minecraft/world/level/chunk/storage/SimpleRegionStorage", SELECTED,
           "net/minecraft/server/level/WorldGenRegion")
GUARD_CONTROLS = ("jar-hash", "class-hash", "config-hash", "missing-config", "duplicate-config",
                  "not-required", "config-priority", "explicit-class-priority", "missing-mixin-annotation", "mixin-version")
WOVEN_CONTROLS = ("body", "constant-body", "duplicate-handler", "priority", "early-return", "missing-head",
                  "missing-return", "bypass-head", "worldgen-body", "worldgen-true", "worldgen-extra-cast")
TEMPLATE_HASHES = {
    "src/zone/moddev/orderprobe/ProbeGuard.java": "AF284EC4E6826701085BECC159A1984095DD3429CEA85D2BA3160E4399BAD53B",
    "src/zone/moddev/orderprobe/OrderProbeAudit.java": "46AE94FB298E1683C1B71B149A307AF42BEBECAE875EE8658F7F104E6509C6C3",
    "src/zone/moddev/orderprobe/OrderProbePlugin.java": "21DEABC1DBECCAFE49CBD67F13B44B10426BD4F0A874F1438CA06AD4ECE33FE5",
    "src/zone/moddev/orderprobe/OrderProbeMod.java": "54E37CE043BD3DB401E9D8ED383A112340B3DD21A8729716AC1929800CD68582",
    "test/WovenNegativeControl.java": "08C5840485E84F58B1D1C698F508F7A6F6A8A74DFA181CE73553B5877FD139FA",
    "test/run_guard_controls.py": "307CCF9BEBDD58E242A7B4D961D372803E16315B0D2F8286DBCCEC3A7A0DDDC3",
    "test/run_woven_controls.py": "A6892AAADBF027953569D71C9EC9FCA33BB539CFBB47BD2C563CA63D03A26F81",
    "resources/META-INF/mods.toml": "01A57052CC5BD5CEA7458BBD75ADFEE6C0F7E01B73DD431420BE570F5658A954",
    "resources/advantageorderprobe.mixins.json": "1652355A2C5397D6F34CC7F07C8355D85F830876E107D32625A98F459E7644DC",
}


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise AssertionError("Unreviewed order-probe template drift: " + old[:90])
    return text.replace(old, new, 1)


LOCK_AUDIT = '''        MethodNode selected = method(level, "getUnfixedDataTag", "(Z)Lcom/mojang/serialization/Dynamic;");
        MethodNode nativeSelected = method(exported(nativeClasses, LEVEL), "getUnfixedDataTag", selected.desc);
        List<AbstractInsnNode> selectedCode = instructions(selected);
        int lockedAt = -1, rawAt = -1;
        List<Integer> capturedAt = new ArrayList<>();
        for (int i = 0; i < selectedCode.size(); i++) {
            if (!(selectedCode.get(i) instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(LEVEL) && call.name.equals("checkLock") && call.desc.equals("()V")) {
                ProbeGuard.require(lockedAt < 0, "duplicate selected-world lock check"); lockedAt = i;
            }
            if (call.owner.equals("net/minecraft/world/level/storage/LevelStorageSource")
                    && call.name.equals("readLevelDataTagRaw")
                    && call.desc.equals("(Ljava/nio/file/Path;)Lnet/minecraft/nbt/CompoundTag;")) {
                ProbeGuard.require(rawAt < 0, "duplicate selected-world raw read"); rawAt = i;
            }
            if (worldHeads.containsKey(call.name)) capturedAt.add(i);
            ProbeGuard.require(!call.name.equals("isCancelled"), "selected-world capture became cancellable");
        }
        ProbeGuard.require(lockedAt >= 0 && capturedAt.size() == 2 && lockedAt < capturedAt.getFirst()
                && capturedAt.getLast() < rawAt, "capture is not after session lock and before raw read");
        for (int site : capturedAt) {
            ProbeGuard.require(dominates(selected, lockedAt, site), "selected-world lock can be bypassed");
            ProbeGuard.require(dominates(selected, site, rawAt), "selected-world capture can be bypassed");
        }
        ProbeGuard.require(dominates(selected, capturedAt.getFirst(), capturedAt.getLast()), "capture order can be bypassed");
        ProbeGuard.require(selected.tryCatchBlocks.stream().map(row -> row.type).toList().equals(
                nativeSelected.tryCatchBlocks.stream().map(row -> row.type).toList()), "selected-world exception coverage changed");
        List<String> levelOrder = sequence(selected, worldHeads);'''


def retarget(relative, text):
    if relative.endswith("OrderProbeAudit.java"):
        text = replace_once(text, '"net/minecraft/world/level/storage/LevelStorageSource";', '"' + SELECTED + '";')
        text = replace_once(text, '+ target.name.substring(target.name.lastIndexOf(\'/\') + 1) + "Mixin";',
                            '+ (target.name.equals(LEVEL) ? (mod.equals("basepower") ? "SelectedWorldStorageMixin" : "LevelStorageSourceMixin")\n'
                            '                : target.name.substring(target.name.lastIndexOf(\'/\') + 1) + "Mixin");')
        text = replace_once(text, '"(" + TAG + "I" + TAG + ")" + TAG', '"(" + TAG + "I" + TAG + "I)" + TAG')
        text = replace_once(text, '        List<String> levelOrder = sequence(method(level, "readLevelDataTagFixed", null), worldHeads);', LOCK_AUDIT)
        text = replace_once(text, '        result.addProperty("exceptionEdgesChecked", true);',
                            '        result.addProperty("exceptionEdgesChecked", true);\n'
                            '        result.addProperty("minecraft", "26.2"); result.addProperty("forge", "65.1.0");\n'
                            '        result.addProperty("dataVersion", 4903); result.addProperty("upgradeDescriptor", upgrade.desc);\n'
                            '        result.addProperty("selectedWorldAfterLock", true);\n'
                            '        result.addProperty("selectedWorldTarget", LEVEL);\n'
                            '        result.addProperty("rawReadDescriptor", "(Ljava/nio/file/Path;)Lnet/minecraft/nbt/CompoundTag;");')
    elif relative.endswith("OrderProbePlugin.java"):
        text = replace_once(text, "native61-order-plugin-", "native65-order-plugin-")
        text = replace_once(text, "NATIVE61_ORDER_PROBE_PRIORITIES", "NATIVE65_ORDER_PROBE_PRIORITIES")
    elif relative.endswith("WovenNegativeControl.java"):
        text = replace_once(text, "(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", UPGRADE)
    elif relative.endswith(".py"):
        text = replace_once(text, "native12111 as native", "native262 as native")
        text = replace_once(text, "native12111_order_probe", "native262_transformer_order")
        text = replace_once(text, '"--release", "21"', '"--release", "25"')
    elif relative.endswith("mods.toml"):
        for old, new in (('[61,)', '[65,)'), ('1.0.0.121111', VERSION), ('Native61', 'Native65'),
                         ('[61.1.0]', '[65.1.0]'), ('[1.21.11]', '[26.2]')):
            text = replace_once(text, old, new)
    return text


def prepare_sources(output):
    payloads = {}
    for relative, checksum in TEMPLATE_HASHES.items():
        path = TEMPLATES / relative
        upgrade.validate_hash(path, checksum)
        payloads[relative] = retarget(relative, path.read_text(encoding="utf-8"))
    # Fail before creating any output if a template is stale.
    output.mkdir(parents=True, exist_ok=False)
    for relative, text in payloads.items():
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
    return fingerprint(output)


def builder():
    spec = importlib.util.spec_from_file_location("native262_pinned_probe_builder", TEMPLATES / "build.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_files(hashes):
    for path, checksum in hashes.items():
        upgrade.validate_hash(Path(path), checksum)


def verify_probe_package(path):
    required = ("META-INF/mods.toml", "advantageorderprobe.mixins.json", "order-probe-pins.json",
                *("zone/moddev/orderprobe/" + name + ".class" for name in
                  ("OrderProbeMod", "OrderProbeAudit", "OrderProbePlugin", "ProbeGuard")))
    with ZipFile(path) as archive:
        for name in required:
            if archive.namelist().count(name) != 1:
                raise AssertionError("Missing/duplicate order sidecar entry: " + name)
        metadata = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8"))
        if [(row.get("modId"), row.get("version")) for row in metadata.get("mods", [])] != [(MOD_ID, VERSION)]:
            raise AssertionError("Order sidecar must declare exactly its own current mod identity")


def build_sidecar(profile, seed, java, output):
    native = upgrade.engine()
    java, _ = native.java25(java)
    baseline = builder()
    classes = copy.deepcopy(baseline.CLASSES)
    classes["basepower"].insert(2, "SelectedWorldStorageMixin")
    pins = bind(baseline.base_pins, native=native, CLASSES=classes, RIG=RIG)(profile, seed)
    classpath_file = RIG.parent / ".classpath"
    classpath = [Path(row.attrib["path"]).resolve(strict=True)
                 for row in ET.parse(classpath_file).getroot().findall("classpathentry") if row.attrib.get("kind") == "lib"]
    if not classpath or any(not path.is_file() for path in classpath):
        raise AssertionError("Missing sealed javac dependencies")
    engine = Path(seed) / "libraries/net/minecraftforge/forge/26.2-65.1.0/forge-26.2-65.1.0-server.jar"
    engine_sha = digest(engine)
    output = output_path(output)
    for path in (profile, seed, java, classpath_file, TEMPLATES, Path(__file__), *classpath):
        require_disjoint(output, path)
    output.mkdir(parents=True, exist_ok=False)
    source_tree = output / "source-tree"
    sources = prepare_sources(source_tree)
    compiled = output / "classes"
    compiled.mkdir()
    cp_hashes = {str(path): digest(path) for path in classpath}
    command = [str(java.with_name("javac.exe" if os.name == "nt" else "javac")), "-proc:none", "--release", "25",
               "-cp", os.pathsep.join(map(str, classpath)), "-d", str(compiled), *map(str, sorted((source_tree / "src").rglob("*.java")))]
    completed = subprocess.run(command, env=native.native_launch_environment(), capture_output=True, text=True, timeout=120)
    (output / "javac.log").write_text(completed.stdout + completed.stderr, encoding="utf-8")
    if completed.returncode:
        raise AssertionError("Order probe compilation failed: " + str(output / "javac.log"))
    native_classes = output / "native-classes"
    with ZipFile(engine) as original:
        for name in TARGETS:
            path = native_classes / (name + ".class")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(baseline.unique_entry(original, name + ".class"))
    variants = {}
    for mode in MODES:
        directory = output / mode
        directory.mkdir()
        selected = copy.deepcopy(pins)
        selected.update(mode=mode, priorities={"basepower.mixins.json": 900 if mode == "power-low" else 1100,
                                               "basemetals.mixins.json": 1100 if mode == "power-low" else 900})
        raw = (json.dumps(selected, indent=2, sort_keys=True) + "\n").encode()
        plan = directory / "order-probe-pins.json"
        plan.write_bytes(raw)
        files = {path.relative_to(compiled).as_posix(): path.read_bytes() for path in compiled.rglob("*.class")}
        files.update({path.relative_to(source_tree / "resources").as_posix(): path.read_bytes()
                      for path in (source_tree / "resources").rglob("*") if path.is_file()})
        files["order-probe-pins.json"] = raw
        files["META-INF/MANIFEST.MF"] = ("Manifest-Version: 1.0\r\nMixinConfigs: advantageorderprobe.mixins.json\r\n"
                                       "Automatic-Module-Name: advantageorderprobe\r\n\r\n").encode()
        jar = directory / ("AdvantageOrderProbe-" + VERSION + ".jar")
        baseline.archive(jar, files)
        verify_probe_package(jar)
        variants[mode] = dict(jar=str(jar), sha256=digest(jar), pins=str(plan), pinsSha256=digest(plan))
    verify_files(cp_hashes)
    if fingerprint(source_tree) != sources or digest(engine) != engine_sha:
        raise AssertionError("Probe build inputs changed")
    result = dict(scope=SCOPE, qualificationAllowed=False, minecraft=upgrade.MINECRAFT, forge=upgrade.FORGE,
                  profile=str(profile), profileSha256=digest(profile), javaSha256=digest(java), sourceTree=str(source_tree),
                  sourceFingerprint=sources, templateHashes=TEMPLATE_HASHES, builderSha256=digest(TEMPLATES / "build.py"),
                  adapterSha256=digest(Path(__file__)), classpathFile=str(classpath_file), classpathFileSha256=digest(classpath_file),
                  classpath=cp_hashes, nativeEngine=str(engine), nativeEngineSha256=engine_sha,
                  nativeClasses=str(native_classes), nativeClassHashes=fingerprint(native_classes), variants=variants)
    write_json(output / "build-receipt.json", result)
    return result


def verify_woven(proof):
    required = dict(status="PASS", scope=SCOPE, qualificationAllowed=False, minecraft=upgrade.MINECRAFT,
                    forge=upgrade.FORGE, dataVersion=4903, upgradeDescriptor=UPGRADE,
                    selectedWorldAfterLock=True, selectedWorldTarget=SELECTED, rawReadDescriptor=RAW_READ,
                    allHandlerBodiesMatchPinnedProduction=True, nativeReturnsCovered=2, exceptionEdgesChecked=True)
    if any(type(proof.get(key)) is not type(value) or proof[key] != value for key, value in required.items()):
        raise AssertionError("Missing Forge65 locked-world / four-argument woven proof")
    prior.compare_orders(proof, {**proof, **{name: ([list(reversed(pair)) for pair in proof[name]] if name == "regionReturns"
                                                  else list(reversed(proof[name]))) for name in VECTORS}})
    return proof


def compare_orders(first, second):
    verify_woven(first)
    verify_woven(second)
    return prior.compare_orders(first, second)


verify_plugin = prior.verify_plugin


def diagnostic_manifest(runtime, label):
    path = Path(runtime) / ("order-export-" + label + ".json")
    document = dict(format=1, purpose="NATIVE65_MIXIN_EXPORT_DIAGNOSTIC", runtime=str(Path(runtime).resolve()), label=label,
                    properties={"mixin.debug.export": True, "mixin.debug.export.decompile": False})
    with path.open("x", encoding="utf-8") as output:
        json.dump(document, output, sort_keys=True, indent=2)
        output.write("\n")
    return path, digest(path)


def archive_exports(runtime, folder):
    runtime, folder = Path(runtime).resolve(strict=True), Path(folder).resolve(strict=True)
    folder.relative_to(runtime)
    source = runtime / ".mixin.out"
    source.resolve(strict=True).relative_to(runtime)
    for target in TARGETS:
        upgrade.regular_bytes(source / "class" / (target + ".class"))
    for path in source.rglob("*"):
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
            raise AssertionError("Aliased Mixin export input")
        if path.is_file():
            upgrade.regular_bytes(path)
    shutil.copytree(source / "class", folder / "export")
    source.rename(folder / "mixin-output")
    return fingerprint(folder / "mixin-output")


def audit_command(java, build, variant, runtime, exports, output):
    proof = bind(prior.audit_command, native=upgrade.engine())(java, build, variant, runtime, exports, output)
    return verify_woven(proof)


def instrumented_stage(variant):
    def stage(runtime, seed, inventory, mods, port):
        native = upgrade.engine()
        upgrade.validate_hash(variant["jar"], variant["sha256"])
        expected = native.stage(runtime, seed, inventory, mods, port)
        jar = Path(variant["jar"])
        if jar.name in expected:
            raise AssertionError("Order sidecar collides with a production input")
        shutil.copy2(jar, Path(runtime) / "mods" / jar.name)
        expected[jar.name] = variant["sha256"].upper()
        path = Path(runtime) / "input-jars.json"
        inputs = upgrade.read_json(path)
        inputs.append(dict(file=jar.name, source=str(jar), sha256=variant["sha256"].upper(),
                           scope=SCOPE, qualificationAllowed=False))
        write_json(path, inputs)
        native.verify_runtime(runtime, inventory, expected)
        return expected
    return stage


def controls(java, build_path, build, output, *, runtime=None, exports=None):
    woven = runtime is not None
    script = Path(build["sourceTree"]) / "test" / ("run_woven_controls.py" if woven else "run_guard_controls.py")
    command = [sys.executable, "-B", str(script), "--build-receipt", str(build_path), "--java", str(java), "--output", str(output)]
    if woven:
        command.extend(["--runtime", str(runtime), "--exports", str(exports)])
    env = upgrade.engine().native_launch_environment()
    env["PYTHONPATH"] = os.pathsep.join([str(RIG / "migration"), *sys.path])
    done = subprocess.run(command, env=env, capture_output=True, text=True, timeout=300)
    Path(str(output) + ".log").write_text(done.stdout + done.stderr, encoding="utf-8")
    if done.returncode:
        raise AssertionError("Order mutation controls failed: " + str(output))
    path = Path(output) / ("negative-controls.json" if woven else "guard-controls.json")
    receipt = upgrade.read_json(path)
    expected = {"unmodified" if woven else "baseline": "PASS", **dict.fromkeys(WOVEN_CONTROLS if woven else GUARD_CONTROLS, "REJECTED")}
    if (receipt.get("status") != "PASS" or receipt.get("scope") != SCOPE
            or receipt.get("qualificationAllowed") is not False or receipt.get("controls") != expected
            or receipt.get("buildReceiptSha256") != digest(build_path)):
        raise AssertionError("Missing exact 21 mutation controls / positive baselines")
    return dict(file=str(path), sha256=digest(path), **receipt)


def validate_build(build, profile, java):
    if (build.get("scope") != SCOPE or build.get("qualificationAllowed") is not False
            or (build.get("minecraft"), build.get("forge")) != (upgrade.MINECRAFT, upgrade.FORGE)
            or build["profileSha256"] != digest(profile) or build["javaSha256"] != digest(java)
            or build["templateHashes"] != TEMPLATE_HASHES):
        raise AssertionError("Probe is not bound to this exact Forge65 production profile")
    paths = {Path(path): sha for path, sha in build["classpath"].items()}
    paths.update({TEMPLATES / path: sha for path, sha in TEMPLATE_HASHES.items()})
    paths.update({Path(__file__): build["adapterSha256"], TEMPLATES / "build.py": build["builderSha256"],
                  Path(build["nativeEngine"]): build["nativeEngineSha256"],
                  Path(build["classpathFile"]): build["classpathFileSha256"]})
    for variant in build["variants"].values():
        paths.update({Path(variant["jar"]): variant["sha256"], Path(variant["pins"]): variant["pinsSha256"]})
        verify_probe_package(Path(variant["jar"]))
    verify_files(paths)
    for folder, hashes in ((build["nativeClasses"], build["nativeClassHashes"]), (build["sourceTree"], build["sourceFingerprint"])):
        if fingerprint(Path(folder)) != hashes:
            raise AssertionError("Pinned native audit or generated source tree changed")
        paths.update({Path(folder) / name: sha for name, sha in hashes.items()})
    if set(build["variants"]) != set(MODES):
        raise AssertionError("Both pinned order variants are required")
    return paths


def run(args):
    native = upgrade.engine()
    upgrade.read_source(args)
    upgrade.validate_hash(args.build_receipt, args.build_receipt_sha256)
    build = upgrade.read_json(args.build_receipt)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, _ = native.java25(args.java)
    protected = validate_build(build, args.profile, java)
    protected.update({Path(args.build_receipt): digest(args.build_receipt), Path(args.profile): digest(args.profile),
                      Path(args.inventory): digest(args.inventory), java: digest(java), **upgrade.oracle_closure()})
    protected.update({Path(path): checksum for _, path, checksum in target[2].values()})
    trees = {Path(path): fingerprint(Path(path)) for path in (args.source, args.legacy_reference or args.source, args.seed)}
    root = output_path(args.output)
    for path in (args.source, args.legacy_reference or args.source, args.profile, args.seed, args.inventory, java, *protected,
                 *(row[1] for row in target[2].values())):
        require_disjoint(root, path)
    root.mkdir(parents=True, exist_ok=False)
    hook_only = getattr(args, "hook_only", False)
    result = dict(status="RUNNING", scope=SCOPE, qualificationAllowed=False, minecraft=upgrade.MINECRAFT,
                  forge=upgrade.FORGE, profileSha256=digest(args.profile), buildReceiptSha256=digest(args.build_receipt),
                  inputHashes={str(path): sha for path, sha in protected.items()},
                  migrationScope=upgrade.HOOK_SCOPE if hook_only else upgrade.SCOPE,
                  operationRequested=not hook_only, modes={})
    report = root / "native262-transformer-order-results.json"
    write_json(report, result)
    try:
        result["guardControls"] = controls(java, args.build_receipt, build, root / "guard-controls")
        for index, mode in enumerate(MODES):
            variant = build["variants"][mode]
            pins = upgrade.read_json(variant["pins"])
            observations = []
            result["modes"][mode] = dict(status="RUNNING", observations=observations)
            write_json(report, result)

            class OrderServer(native.Server):
                def __init__(self, runtime, java, launcher, label):
                    self.order_archived = False
                    if (Path(runtime) / ".mixin.out").exists():
                        raise AssertionError("Never reuse stale exports from an earlier boot")
                    manifest, checksum = diagnostic_manifest(runtime, label)
                    super().__init__(runtime, java, launcher, label, diagnostic_environment=manifest,
                                     diagnostic_environment_sha256=checksum)

                def close(self):
                    failure = None
                    try:
                        super().close()
                    except BaseException as error:
                        failure = error
                    try:
                        if not self.order_archived:
                            if self.process.poll() is None:
                                raise AssertionError("Never archive an active order-probe JVM")
                            self.order_archived = True
                            runtime = self.native_runtime
                            folder = runtime / "order-evidence" / self.native_label
                            folder.mkdir(parents=True, exist_ok=False)
                            plugin_file = runtime / ("native65-order-plugin-" + str(self.process.pid) + ".json")
                            plugin = upgrade.read_json(plugin_file)
                            verify_plugin(plugin, pins, runtime, self.process.pid)
                            if plugin["pinsSha256"] != variant["pinsSha256"]:
                                raise AssertionError("Plugin resource pins changed")
                            shutil.copy2(plugin_file, folder / "plugin.json")
                            exports = archive_exports(runtime, folder)
                            woven = audit_command(java, build, variant, runtime, folder / "export", folder / "audit.json")
                            launch = upgrade.read_json(runtime / ("native65-launch-environment-" + self.native_label + ".json"))
                            if launch["status"] != "DIAGNOSTIC_NOT_QUALIFIED" or launch["qualificationAllowed"] is not False:
                                raise AssertionError("Diagnostic launch was relabelled production qualified")
                            observations.append(dict(runtime=str(runtime), label=self.native_label, folder=str(folder), audit=woven,
                                                     auditSha256=digest(folder / "audit.json"), pluginSha256=digest(plugin_file),
                                                     fullExportHashes=exports))
                    except BaseException as audit:
                        if failure is None:
                            raise
                        failure.add_note("Order archive/audit also failed: " + repr(audit))
                    if failure is not None:
                        raise failure

            values = SimpleNamespace(**vars(args))
            values.output, values.port = root / mode, args.port + index
            expected_boots = 2 if hook_only else 3
            if upgrade.run(values, server_type=OrderServer, stage_function=instrumented_stage(variant),
                           hook_only=hook_only) or len(observations) != expected_boots:
                raise AssertionError("Missing real woven process proofs for the explicitly requested migration scope")
            signature = {key: observations[0]["audit"][key] for key in VECTORS}
            if any({key: observation["audit"][key] for key in VECTORS} != signature for observation in observations):
                raise AssertionError("Actual callback order changed across cold boots")
            result["modes"][mode] = dict(status="PASS", observations=observations)
            write_json(report, result)
        first = result["modes"][MODES[0]]["observations"][0]
        result["wovenControls"] = controls(java, args.build_receipt, build, root / "woven-controls",
                                           runtime=Path(first["runtime"]), exports=Path(first["folder"]) / "export")
        result["permutationProof"] = compare_orders(first["audit"], result["modes"][MODES[1]]["observations"][0]["audit"])
        result["status"] = "PASS"
    except BaseException as failure:
        result.update(status="FAIL", error=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            validate_build(build, args.profile, java)
            result["inputsUnchanged"] = all(digest(path) == sha.upper() for path, sha in protected.items())
            result["protectedTreesUnchanged"] = all(fingerprint(path) == hashes for path, hashes in trees.items())
            if not result["inputsUnchanged"] or not result["protectedTreesUnchanged"]:
                raise AssertionError("Pinned order inputs changed")
        except Exception as audit:
            result.update(status="FAIL", custodyFailure=repr(audit))
        write_json(report, result)
    return int(result["status"] != "PASS")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "build":
        parser = argparse.ArgumentParser(description="Compile the local diagnostic sidecar without launching Minecraft")
        for name in ("profile", "seed", "inventory", "java", "output"):
            parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
        parser.add_argument("--inventory-sha256", required=True)
        args = parser.parse_args(sys.argv[2:])
        native = upgrade.engine()
        native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
        build_sidecar(args.profile, args.seed, args.java, args.output)
        return 0
    parser = upgrade.parser()
    parser.description = __doc__
    parser.add_argument("--hook-only", action="store_true", help="Explicit non-consuming conversion/restart proof; never operating qualification")
    parser.add_argument("--build-receipt", type=lambda value: Path(value).resolve())
    parser.add_argument("--build-receipt-sha256")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65534:
        parser.error("Require two assigned consecutive unprivileged ports")
    if args.build_receipt is None or args.build_receipt_sha256 is None:
        parser.error("Require the independently pinned sidecar build receipt and SHA")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
