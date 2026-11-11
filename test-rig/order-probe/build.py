"""Independent javac build of a pinned local-only order sidecar; no Gradle or target rewrites."""

import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent
RIG = ROOT.parent
sys.path.insert(0, str(RIG / "migration"))
from matrix import digest, fingerprint, write_json
import native12111 as native

MODS = {"basepower", "basemetals", "basesteam", "baseelectric", "orespawn", "advantageworkstest"}
CLASSES = {"basepower": ["BlockStateDataMixin", "LevelStorageSourceMixin", "PlayerDataStorageMixin",
                          "SimpleRegionStorageMixin", "WorldGenRegionMixin"],
           "basemetals": ["AnvilBlockMixin", "AnvilMenuMixin", "BlockStateDataMixin", "LevelCapabilityDataMixin",
                           "LevelStorageSourceMixin", "OreSpawnEarlyTagCompatibilityMixin", "SimpleRegionStorageMixin", "WorldGenRegionMixin"]}
TARGETS = ("net/minecraft/world/level/chunk/storage/SimpleRegionStorage",
           "net/minecraft/world/level/storage/LevelStorageSource", "net/minecraft/server/level/WorldGenRegion")
VERSION = "1.0.0.121111"
JAR = f"AdvantageOrderProbe-{VERSION}.jar"


def unique_entry(archive, name):
    if archive.namelist().count(name) != 1:
        raise AssertionError("Missing/duplicate pinned resource: " + name)
    return archive.read(name)


def sha(payload):
    import hashlib
    return hashlib.sha256(payload).hexdigest().upper()


def base_pins(profile, seed):
    document = native.read_json(profile)
    entries = document["mods"]
    if len(entries) != 6 or {entry["id"] for entry in entries} != MODS:
        raise AssertionError("Order probe requires the exact six-jar production profile")
    pins = dict(format=1, mixinVersion="0.8.7", profileSha256=digest(profile), mods={}, configs={})
    for entry in entries:
        path = (RIG / entry["path"]).resolve(strict=True)
        native.validate_hash(path, entry["sha256"])
        pins["mods"][entry["id"]] = dict(file=path.name, sha256=digest(path))
        if entry["id"] not in CLASSES:
            continue
        name = entry["id"] + ".mixins.json"
        with ZipFile(path) as archive:
            raw = unique_entry(archive, name)
            config = json.loads(raw)
            if (config["required"] is not True or config["compatibilityLevel"] != "JAVA_21"
                    or config["injectors"] != {"defaultRequire": 1} or config["mixins"] != CLASSES[entry["id"]]
                    or any(key in config for key in ("plugin", "priority", "mixinPriority", "client", "server"))):
                raise AssertionError("Production Mixin config contract changed: " + name)
            classes = {config["package"] + "." + short: sha(unique_entry(archive,
                       (config["package"] + "." + short).replace(".", "/") + ".class")) for short in config["mixins"]}
        pins["configs"][name] = dict(mod=entry["id"], sha256=sha(raw), package=config["package"],
                                     mixins=config["mixins"], classes=classes)
    mixin = seed / "libraries/org/spongepowered/mixin/0.8.7/mixin-0.8.7.jar"
    pins["mixinJarSha256"] = digest(mixin)
    with ZipFile(mixin) as archive:
        pins["mixinClasses"] = {name: sha(unique_entry(archive, name.replace(".", "/") + ".class"))
                                for name in ("org.spongepowered.asm.mixin.MixinEnvironment",
                                             "org.spongepowered.asm.mixin.transformer.Config",
                                             "org.spongepowered.asm.mixin.transformer.MixinConfig",
                                             "org.spongepowered.asm.mixin.transformer.MixinInfo",
                                             "org.spongepowered.asm.mixin.transformer.MixinProcessor")}
    return pins


def archive(path, files):
    with ZipFile(path, "x", compression=ZIP_DEFLATED) as output:
        for name, data in sorted(files.items()):
            item = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            item.compress_type = ZIP_DEFLATED
            output.writestr(item, data)


def build(profile, seed, java, output):
    java, _ = native.java21(java)
    pins = base_pins(profile, seed)
    classpath_file = RIG.parent / ".classpath"
    classpath = [Path(row.attrib["path"]) for row in ET.parse(classpath_file).getroot().findall("classpathentry")
                 if row.attrib.get("kind") == "lib"]
    for path in classpath:
        if not path.is_file():
            raise FileNotFoundError("Pinned Eclipse build dependency is absent: " + str(path))
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    sources = fingerprint(ROOT)
    command = [str(java.with_name("javac.exe" if os.name == "nt" else "javac")), "-proc:none", "--release", "21",
               "-cp", os.pathsep.join(map(str, classpath)), "-d", str(classes), *map(str, sorted((ROOT / "src").rglob("*.java")))]
    completed = subprocess.run(command, env=native.native_launch_environment(), capture_output=True, text=True)
    (output / "javac.log").write_text(completed.stdout + completed.stderr, encoding="utf-8")
    if completed.returncode:
        raise RuntimeError("Sidecar javac failed; retained " + str(output / "javac.log"))
    native_classes = output / "native-classes"
    engine = RIG / ".gradle/mavenizer/repo/net/minecraftforge/forge/1.21.11-61.1.0/forge-1.21.11-61.1.0.jar"
    with ZipFile(engine) as original:
        for name in TARGETS:
            path = native_classes / (name + ".class")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(unique_entry(original, name + ".class"))
    variants = {}
    for mode in ("power-low", "metals-low"):
        directory = output / mode
        directory.mkdir()
        selected = copy.deepcopy(pins)
        selected.update(mode=mode, priorities={"basepower.mixins.json": 900 if mode == "power-low" else 1100,
                                              "basemetals.mixins.json": 1100 if mode == "power-low" else 900})
        raw = (json.dumps(selected, indent=2, sort_keys=True) + "\n").encode()
        plan = directory / "order-probe-pins.json"
        plan.write_bytes(raw)
        files = {str(path.relative_to(classes)).replace("\\", "/"): path.read_bytes() for path in classes.rglob("*.class")}
        files.update({str(path.relative_to(ROOT / "resources")).replace("\\", "/"): path.read_bytes()
                      for path in (ROOT / "resources").rglob("*") if path.is_file()})
        files["order-probe-pins.json"] = raw
        files["META-INF/MANIFEST.MF"] = ("Manifest-Version: 1.0\r\nMixinConfigs: advantageorderprobe.mixins.json\r\n"
                                          "Automatic-Module-Name: advantageorderprobe\r\n\r\n").encode()
        jar = directory / JAR
        archive(jar, files)
        variants[mode] = dict(jar=str(jar), sha256=digest(jar), pins=str(plan), pinsSha256=digest(plan))
    if fingerprint(ROOT) != sources:
        raise AssertionError("Order sidecar sources changed during build")
    receipt = dict(scope="INSTRUMENTED_ORDER_TEST_ONLY", qualificationAllowed=False, profile=str(profile),
                   profileSha256=digest(profile), javaSha256=digest(java), sourceFingerprint=sources,
                   classpathFileSha256=digest(classpath_file), classpath={str(path): digest(path) for path in classpath},
                   nativeEngineSha256=digest(engine), nativeClasses=str(native_classes),
                   nativeClassHashes=fingerprint(native_classes), variants=variants)
    write_json(output / "build-receipt.json", receipt)
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "java", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.profile, args.seed, args.java, args.output)["variants"], indent=2))
