"""Local instrumented Mixin-order proof, never normal production qualification."""

import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace
import traceback

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
import native12111 as native
import native12111_upgrade as upgrade

SCOPE = "INSTRUMENTED_ORDER_TEST_ONLY"
MOD_ID = "advantageorderprobe"
VERSION = "1.0.0.121111"
MOD_IDS = {"basepower", "basemetals", "basesteam", "baseelectric", "orespawn", "advantageworkstest"}
MODES = ("power-low", "metals-low")
VECTORS = ("regionHeads", "regionReturns", "worldHeads", "worldgenHeads")


def variant_profile(base, sidecar, output):
    result = copy.deepcopy(base)
    if len(result["mods"]) != 6 or {row["id"] for row in result["mods"]} != MOD_IDS:
        raise AssertionError("Order test requires exactly six pinned production inputs")
    path = Path(sidecar["jar"]).resolve(strict=True)
    native.validate_hash(path, sidecar["sha256"])
    result["mods"].append(dict(id=MOD_ID, version=VERSION, file=path.name, path=str(path), sha256=sidecar["sha256"]))
    result["orderProbe"] = dict(scope=SCOPE, qualificationAllowed=False)
    write_json(output, result)
    return result


def verify_plugin(document, pins, runtime, pid):
    if (document.get("status") != SCOPE or document.get("qualificationAllowed") is not False
            or document.get("mode") != pins["mode"] or document.get("runtime") != str(runtime.resolve())
            or document.get("pid") != pid or document.get("preparedBefore") is not False):
        raise AssertionError("Plugin proof does not bind this instrumented boot")
    before, after = document["before"], document["after"]
    if set(before) != set(after) or not set(pins["priorities"]) <= set(before):
        raise AssertionError("Registered config set changed")
    for name, value in before.items():
        expected = pins["priorities"].get(name, value)
        if name in pins["priorities"] and value != 1000:
            raise AssertionError("Child priority was already modified")
        if after[name] != expected:
            raise AssertionError("Unexpected/missing priority mutation")


def compare_orders(first, second):
    def pair(value):
        return isinstance(value, list) and len(value) == 2 and set(value) == {"basepower", "basemetals"}

    for record in (first, second):
        if (record.get("status") != "PASS" or record.get("scope") != SCOPE
                or record.get("qualificationAllowed") is not False
                or record.get("allHandlerBodiesMatchPinnedProduction") is not True
                or record.get("nativeReturnsCovered") != 2 or record.get("exceptionEdgesChecked") is not True):
            raise AssertionError("Missing strict woven-bytecode proof")
    for name in VECTORS:
        left, right = first[name], second[name]
        if name == "regionReturns":
            if len(left) != 2 or len(right) != 2:
                raise AssertionError("Native return paths missing")
            pairs = zip(left, right)
        else:
            pairs = [(left, right)]
        for before, after in pairs:
            if not pair(before) or not pair(after) or after != list(reversed(before)):
                raise AssertionError("Actual emitted order did not reverse: " + name)
    return dict(status="PASS", scope=SCOPE, qualificationAllowed=False,
                permutations={name: [first[name], second[name]] for name in VECTORS})


def diagnostic_manifest(runtime, label):
    path = runtime / ("order-export-" + label + ".json")
    document = dict(format=1, purpose="NATIVE61_MIXIN_EXPORT_DIAGNOSTIC", runtime=str(runtime.resolve()), label=label,
                    properties={"mixin.debug.export": True, "mixin.debug.export.decompile": False})
    with path.open("x", encoding="utf-8") as output:
        json.dump(document, output, sort_keys=True, indent=2)
        output.write("\n")
    return path, digest(path)


def audit_command(java, build, variant, runtime, exports, output):
    classpath = os.pathsep.join([variant["jar"], *build["classpath"]])
    result = subprocess.run([str(java), "-cp", classpath, "zone.moddev.orderprobe.OrderProbeAudit", "audit",
                             variant["pins"], str(runtime), str(exports), build["nativeClasses"], str(output)],
                            env=native.native_launch_environment(), capture_output=True, text=True, timeout=60)
    output.with_suffix(".log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise AssertionError("Woven bytecode audit failed; retained " + str(output.with_suffix(".log")))
    proof = native.read_json(output)
    if proof.get("scope") != SCOPE or proof.get("qualificationAllowed") is not False:
        raise AssertionError("Audit proof qualification boundary changed")
    return proof


def run(args):
    output = output_path(args.output)
    native.validate_hash(args.build_receipt, args.build_receipt_sha256)
    build = native.read_json(args.build_receipt)
    base = native.read_json(args.profile)
    if build.get("scope") != SCOPE or build.get("qualificationAllowed") is not False or build["profileSha256"] != digest(args.profile):
        raise AssertionError("Sidecar build is not bound to this exact production profile")
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, _ = native.java21(args.java)
    if digest(java) != build["javaSha256"]:
        raise AssertionError("Java executable differs from sidecar build")
    for path, sha in build["classpath"].items():
        native.validate_hash(Path(path), sha)
    native_classes = Path(build["nativeClasses"])
    if fingerprint(native_classes) != build["nativeClassHashes"]:
        raise AssertionError("Native audit reference bytes changed")
    source_root = Path(__file__).resolve().parents[1] / "order-probe"
    def compiled_sources(values):
        return {key: value for key, value in values.items()
                if key.replace("\\", "/").startswith(("src/", "resources/")) or key == "build.py"}
    if compiled_sources(fingerprint(source_root)) != compiled_sources(build["sourceFingerprint"]):
        raise AssertionError("Compiled sidecar source inputs changed since the pinned build")
    for protected in (args.profile, args.seed, args.inventory, args.source, args.legacy_reference,
                      args.build_receipt, *[value[1] for value in target[2].values()]):
        require_disjoint(output, protected)
    for mode in MODES:
        for key, checksum in (("jar", "sha256"), ("pins", "pinsSha256")):
            native.validate_hash(Path(build["variants"][mode][key]), build["variants"][mode][checksum])
    output.mkdir(parents=True)
    source_before, seed_before = fingerprint(args.source), fingerprint(args.seed)
    reference_before = fingerprint(args.legacy_reference)
    protected_inputs = {args.profile: digest(args.profile), args.build_receipt: digest(args.build_receipt),
                        args.inventory: digest(args.inventory), java: digest(java), Path(__file__): digest(Path(__file__)),
                        Path(native.__file__): digest(Path(native.__file__)), Path(upgrade.__file__): digest(Path(upgrade.__file__))}
    protected_inputs.update({Path(path): checksum for path, checksum in build["classpath"].items()})
    protected_inputs.update({native_classes / path: checksum for path, checksum in build["nativeClassHashes"].items()})
    for variant in build["variants"].values():
        protected_inputs[Path(variant["jar"])] = variant["sha256"]
        protected_inputs[Path(variant["pins"])] = variant["pinsSha256"]
    shutil.copy2(Path(__file__), output / "runner-source.py")
    result = dict(status="RUNNING", scope=SCOPE, qualificationAllowed=False, profileSha256=digest(args.profile),
                  buildReceiptSha256=digest(args.build_receipt), runnerSha256=digest(Path(__file__)), modes={})
    report = output / "native12111-order-probe-results.json"
    write_json(report, result)
    try:
        for index, mode in enumerate(MODES):
            variant = build["variants"][mode]
            pins = native.read_json(Path(variant["pins"]))
            profile = output / (mode + "-profile.json")
            variant_profile(base, variant, profile)
            observations = []

            class OrderServer(native.Server):
                def __init__(self, runtime, java, launcher, label):
                    self.order_archived = False
                    manifest, checksum = diagnostic_manifest(Path(runtime), label)
                    super().__init__(runtime, java, launcher, label, diagnostic_environment=manifest,
                                     diagnostic_environment_sha256=checksum)

                def close(self):
                    try:
                        super().close()
                    finally:
                        if not self.order_archived:
                            self.order_archived = True
                            runtime = self.native_runtime
                            folder = runtime / "order-evidence" / self.native_label
                            folder.mkdir(parents=True, exist_ok=False)
                            plugin_file = runtime / ("native61-order-plugin-" + str(self.process.pid) + ".json")
                            plugin = native.read_json(plugin_file)
                            verify_plugin(plugin, pins, runtime, self.process.pid)
                            if plugin["pinsSha256"] != variant["pinsSha256"]:
                                raise AssertionError("Plugin resource pins changed")
                            shutil.copy2(plugin_file, folder / "plugin.json")
                            shutil.copytree(runtime / ".mixin.out/class", folder / "export")
                            woven = audit_command(java, build, variant, runtime, folder / "export", folder / "audit.json")
                            launch = native.read_json(runtime / ("native61-launch-environment-" + self.native_label + ".json"))
                            if launch["status"] != "DIAGNOSTIC_NOT_QUALIFIED" or launch["qualificationAllowed"] is not False:
                                raise AssertionError("Diagnostic launch was relabelled as production qualified")
                            observations.append(dict(runtime=str(runtime), label=self.native_label, audit=woven,
                                                     auditSha256=digest(folder / "audit.json"), pluginSha256=digest(plugin_file)))

            with_server = bind(upgrade.with_server, Server=OrderServer)
            operate = bind(upgrade.operate, with_server=with_server)
            campaign = bind(upgrade.run, with_server=with_server, operate=operate)
            values = SimpleNamespace(**vars(args))
            values.profile, values.output, values.port = profile, output / mode / "original110", args.port + index
            code = campaign(values)
            if code or len(observations) != 3:
                raise AssertionError("Original110 conversion/restart/operation did not complete with three woven proofs")
            signature = {key: observations[0]["audit"][key] for key in VECTORS}
            if any({key: observation["audit"][key] for key in VECTORS} != signature for observation in observations):
                raise AssertionError("Actual order changed between cold boots")
            result["modes"][mode] = dict(status="PASS", observations=observations)
            write_json(report, result)
        result["permutationProof"] = compare_orders(result["modes"][MODES[0]]["observations"][0]["audit"],
                                                     result["modes"][MODES[1]]["observations"][0]["audit"])
        result["status"] = "PASS"
    except BaseException as failure:
        result.update(status="FAIL", error=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(args.source) == source_before
        result["seedUnchanged"] = fingerprint(args.seed) == seed_before
        result["productionJarsUnchanged"] = all(digest(path) == sha.upper() for _, path, sha in target[2].values())
        result["referenceUnchanged"] = fingerprint(args.legacy_reference) == reference_before
        result["inputsUnchanged"] = all(digest(path) == checksum.upper() for path, checksum in protected_inputs.items())
        result["inputHashes"] = {str(path): checksum for path, checksum in protected_inputs.items()}
        if not all(result[key] for key in ("sourceUnchanged", "seedUnchanged", "productionJarsUnchanged", "referenceUnchanged", "inputsUnchanged")):
            result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output", "source", "legacy-reference", "build-receipt"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    for name in ("inventory-sha256", "receipt-sha256", "build-receipt-sha256"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    if not 26520 <= args.port <= 26528:
        parser.error("Use two assigned consecutive instrumented ports within 26520..26529")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
