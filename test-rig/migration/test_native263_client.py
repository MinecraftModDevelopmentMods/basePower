"""Adapter and actual-SDK preflight tests. No Minecraft client is launched."""

import copy
import json
import nbtlib
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib
from zipfile import ZipFile

from matrix import digest
import native263_client as client


def report_fixture():
    labels = {"inventory": "Inventory", "machines": {f"basepower:unit-{index}": f"Unit {index}" for index in range(39)}}
    machines = [dict(block=key, menu_title=name, expected_title=name, status="PASS", required_text="PASS",
                     tooltip_and_inventory_hitbox="PASS", renders=9, blank_label_controls_rejected=9,
                     background_extraction_calls=1) for key, name in labels["machines"].items()]
    for row in machines:
        row["framebuffer_cases"] = [dict(viewport=list(viewport), fill=fill, slot_pixels={}, left=(viewport[0] - 176) // 2,
                                         top=(viewport[1] - 240) // 2) for viewport in client.VIEWPORTS for fill in range(3)]
        row["garbled_background_control"] = "synthetic-header-test-only"
    gauges = []
    for tank in ("portable", "metal"):
        gauges.append(dict(tank=tank, fluid="empty", amount_mb=0, fill_rows=0, different_pixels=0, render_scale=2))
        for fluid in ("water", "lava", "crude-oil", "refined-oil"):
            for rows in (15, 30, 60):
                gauges.append(dict(tank=tank, fluid=fluid, amount_mb=rows * 10, fill_rows=rows, different_pixels=0, render_scale=2))
    report = dict(status="PASS", minecraft="26.3", forge="66.0.0", advantage_mods=["basepower", "basesteam", "baseelectric"],
                  mod_versions={key: client.native.VERSIONS[key] for key in ("basepower", "basesteam", "baseelectric")},
                  items=109, block_states=100, creative_entries=100, resource_errors=[], primary_resource_status="PASS",
                  scope_results={key: dict(status="PASS") for key in ("registered_resources", "tank_pixels", "machine_gui", "oil_renderer", "recipe_book", "child_geometry")},
                  oil_bucket_identities=True, fluid_rendering=[dict(fluid=key, correct_sprite=True, vertices=4) for key in (
                      "basepower:crude_oil", "basepower:flowing_crude_oil", "basepower:refined_oil", "basepower:flowing_refined_oil", "minecraft:water", "minecraft:lava")],
                  machine_gui_layout_status="PASS", machine_gui_text_status="PASS", machine_gui_text_spec=2,
                  machine_gui_framebuffer_spec=3,
                  machine_gui_layout_count=39, machine_gui_text_checks=351, machine_gui_negative_controls_rejected=356,
                  machine_gui_layouts=machines, tank_gauge_pixel_status="PASS", tank_gauge_pixels=gauges,
                  recipe_book_grouping_status="PASS", recipe_book_grouping=[dict(packet=f"recipes-{mode}-sweep.bin", status="PASS",
                      recipes=10, displays=10, cells=10, item_tags=10) for mode in ("normal", "tech", "apocalyptic")],
                  child_renderer_geometry_status="PASS_WITH_MANUAL_GAPS", child_renderer_geometry=dict(status="PASS_WITH_MANUAL_GAPS",
                      passed_geometry_cases=25, manual_gaps=1, cases=[dict(case="unit-" + str(i), status="PASS") for i in range(25)]
                      + [dict(case="hydroturbine-registration", status="MANUAL_GAP_WORLD_REQUIRED")]),
                  login=dict(status="PASS", realWorld=True, integratedServer=True, nativeComponentsBound=True,
                             loggingInEventObserved=True, connectionConnected=True, clientLoaded=True,
                             syntheticComponentBinding=False, itemHolders=100),
                  projectionCalibration={"synthetic-header-test-only": True}, recipe_registry_ids_match_actual_login=True,
                  experimentalFixtureGate=dict(protocol=client.FIXTURE_PROTOCOL, status="NOT_OBSERVED", actionCount=0))
    report["world_oil_visual"] = {"synthetic-header-test-only": True}
    report["child_geometry_context"] = dict(stage="GENUINELY_WORLDLESS_BEFORE_LOGIN", levelPresent=False,
        playerPresent=False, singleplayerServerPresent=False)
    return report, labels


class ClientAdapterTest(unittest.TestCase):
    def test_offline_username_is_bounded_to_native_ascii_protocol_names(self):
        for name in ("A", "AdvantageProbe", "A_0123456789abcd"):
            self.assertEqual(name, client.validate_username(name))
        for name in ("", "AdvantageClientProbe", "name with space", "name-dash", "caf\u00e9", None, 10):
            with self.assertRaisesRegex(AssertionError, "1..16 ASCII"):
                client.validate_username(name)
    def test_experimental_fixture_policy_rejects_other_profile_receipt_and_copied_level(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "campaign/packaged-current/AdvantageWorksMigration"
            source.mkdir(parents=True)
            versions = {"minecraft": client.native.MINECRAFT, "forge": client.native.FORGE, **client.native.VERSIONS}
            level = nbtlib.File({"Data": nbtlib.Compound({"DataVersion": nbtlib.Int(5023),
                "Version": nbtlib.Compound({"Name": nbtlib.String("26.3")})}),
                "fml": nbtlib.Compound({"LoadingModList": nbtlib.List[nbtlib.Compound]([
                    nbtlib.Compound({"ModId": nbtlib.String(key), "ModVersion": nbtlib.String(value)})
                    for key, value in versions.items()])})})
            level.save(source / "level.dat", gzipped=True)
            copied_world = root / "runtime/saves" / client.CLIENT_WORLD
            copied_world.mkdir(parents=True)
            shutil.copy2(source / "level.dat", copied_world / "level.dat")
            (root / "runtime/client-world-source-files.json").write_text(json.dumps({"level.dat": digest(source / "level.dat")}))
            profile = root / "profile.json"
            mods = [dict(id=key, version=value, path=str(root / (key + ".jar")), sha256=client.hashlib.sha256(key.encode()).hexdigest().upper())
                    for key, value in client.native.VERSIONS.items()]
            profile.write_text(json.dumps(dict(minecraftVersion="26.3", forge=dict(version="26.3-66.0.0"), mods=mods)))
            profile_sha = digest(profile)
            receipt_path = source.parents[1] / "native263-broad-results.json"
            receipt = dict(status="PASS", minecraft="26.3", forge="66.0.0", inputsUnchanged=True, seedUnchanged=True,
                profileSha256=profile_sha, scope="PACKAGED_WALKABLE_WORKS_AND_TWELVE_DRILL_WITNESSES",
                jars={row["id"]: row["sha256"] for row in mods}, profiles={"packaged-current": dict(status="PASS", profile="packaged-current",
                    expectedMods=versions, phases={phase: dict(status="PASS") for phase in ("first", "initial", "operation", "persistence", "restarted")})})
            receipt_path.write_text(json.dumps(receipt))
            policy = client.fixture_policy(root / "runtime", profile, profile_sha, receipt_path, source)
            self.assertEqual(client.FIXTURE_SCOPE, policy["eligibility"])
            self.assertEqual(digest(source / "level.dat"), policy["copiedLevelSha256"])
            with self.assertRaisesRegex(AssertionError, "SHA-256 mismatch"):
                client.fixture_policy(root / "runtime", profile, "0" * 64, receipt_path, source)
            for change in (lambda doc: doc.update(status="FAIL"), lambda doc: doc.update(inputsUnchanged=False),
                    lambda doc: doc.update(profileSha256="0" * 64), lambda doc: doc["profiles"]["packaged-current"]["phases"]["restarted"].update(status="RUNNING"),
                    lambda doc: doc["jars"].update(orespawn="0" * 64)):
                wrong = copy.deepcopy(receipt)
                change(wrong)
                receipt_path.write_text(json.dumps(wrong))
                with self.assertRaises(AssertionError):
                    client.fixture_policy(root / "runtime", profile, profile_sha, receipt_path, source)
            receipt_path.write_text(json.dumps(receipt))
            (copied_world / "level.dat").write_bytes(b"copy")
            with self.assertRaisesRegex(AssertionError, "stopped source/copy"):
                client.fixture_policy(root / "runtime", profile, profile_sha, receipt_path, source)

    def test_failed_native_producer_is_reported_before_success_only_artwork_contract(self):
        failed = dict(status="FAIL", error="Native real-world login/component binding did not complete",
                      loginReadiness=dict(screenClass="unit.UnknownScreen", stage="BEFORE_INTEGRATED_SERVER"))
        before = copy.deepcopy(failed)
        with self.assertRaisesRegex(AssertionError, "Native real-world login/component binding"):
            client.verify_producer(failed)
        self.assertEqual(before, failed)
        client.verify_producer(dict(status="PASS"))
        with self.assertRaisesRegex(AssertionError, "Missing source artwork provenance"):
            client.source_references(dict(status="PASS"), {})

    def test_source_artwork_hashes_and_unique_provider_are_still_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "power.jar"
            name, content = "basepower:textures/gui/unit.png", b"strict-artwork-bytes"
            with ZipFile(path, "w") as jar:
                jar.writestr("assets/basepower/textures/gui/unit.png", content)
            proof = dict(resource=name, sha256=client.hashlib.sha256(content).hexdigest().upper(), width=256, height=256)
            mods = dict(basepower=("unit", path, digest(path)))
            references = client.source_references(dict(source=proof), mods)
            client.require_source(proof, references)
            with self.assertRaisesRegex(AssertionError, "frozen jar bytes"):
                client.require_source({**proof, "sha256": "0" * 64}, references)
            with self.assertRaisesRegex(AssertionError, "Ambiguous"):
                client.source_references(dict(source=proof), {**mods, "other": ("unit", path, digest(path))})

    def test_native_vanilla_root_control_requires_exact_sdk_bytes_and_merged_presence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "client-extra-26.3-20260918.230105.jar"
            resources = {"minecraft:textures/block/stone.png": b"unit-stone-only",
                         "minecraft:atlases/blocks.json": b'{"sources":[]}'}
            with ZipFile(archive, "w") as jar:
                for key, value in resources.items():
                    jar.writestr("assets/" + key.replace(":", "/", 1), value)
            code = dict(archives=[client.record(archive)])
            self.assertEqual("NOT_CAPTURED", client.verify_vanilla_resources(root, code)["status"])
            folder = root / "vanilla-resource-controls"
            folder.mkdir()
            rows = [dict(resource=key, vanilla=dict(present=True, bytes=len(value),
                         sha256=client.hashlib.sha256(value).hexdigest().upper()), merged=[dict(pack="vanilla",
                         bytes=len(value), sha256=client.hashlib.sha256(value).hexdigest().upper())])
                    for key, value in resources.items()]
            original = dict(protocol="NATIVE66_VANILLA_RESOURCE_ROOTS_V1", status="PASS",
                            phase="reload-preparation-before-atlas-upload", resources=rows)
            path = folder / "control-01.json"
            path.write_text(json.dumps(original))
            self.assertEqual("PASS", client.verify_vanilla_resources(root, code)["status"])
            overlay = root / "forge-26.3-66.0.0.jar"
            overlay_data = b'{"sources":[{"type":"unit-forge-overlay"}]}'
            with ZipFile(overlay, "w") as jar:
                jar.writestr("assets/minecraft/atlases/blocks.json", overlay_data)
            code["archives"].append(client.record(overlay))
            original["resources"][1]["merged"].append(dict(pack="mod:forge", bytes=len(overlay_data),
                sha256=client.hashlib.sha256(overlay_data).hexdigest().upper()))
            path.write_text(json.dumps(original))
            self.assertEqual("PASS", client.verify_vanilla_resources(root, code)["status"])
            for change in (lambda doc: doc["resources"][0]["vanilla"].update(present=False),
                           lambda doc: doc["resources"][0]["vanilla"].update(sha256="0" * 64),
                           lambda doc: doc["resources"][0].update(merged=[]),
                           lambda doc: doc["resources"][0]["merged"].append(doc["resources"][0]["merged"][0]),
                           lambda doc: doc["resources"].pop()):
                wrong = copy.deepcopy(original)
                change(wrong)
                path.write_text(json.dumps(wrong))
                with self.assertRaises(AssertionError):
                    client.verify_vanilla_resources(root, code)

    def test_preparation_failure_retains_receipt_only_in_a_new_owned_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def fail(args, owned):
                owned["runtime"] = root
                raise AssertionError("synthetic preparation failure")

            with patch.object(client, "_run", side_effect=fail):
                with self.assertRaises(AssertionError):
                    client.run(None)
            self.assertEqual("FAIL", json.loads((root / client.RESULT).read_text())["status"])
            before = (root / client.RESULT).read_bytes()
            with patch.object(client, "_run", side_effect=AssertionError("unowned preflight failure")):
                with self.assertRaises(AssertionError):
                    client.run(None)
            self.assertEqual(before, (root / client.RESULT).read_bytes())

    def test_actual_native_sdk_launch_is_read_only_and_excludes_mutable_child_outputs(self):
        source = client.native.RIG.parent / "runClient.launch"
        before = source.read_bytes()
        plan = client.inspect_launch(source)
        self.assertEqual(("26.3", "66.0.0", "20260918.230105"), (plan["minecraft"], plan["forge"], plan["mcp"]))
        self.assertEqual("", plan["environment"]["MOD_CLASSES"])
        self.assertEqual(before, source.read_bytes())
        self.assertGreater(len(plan["archives"]), 50)
        self.assertTrue(all(not client.mod_ids(row["path"]) & client.FAMILY for row in plan["archives"]))
        self.assertEqual(client.ASSET_INDEX_SHA1, "917FF6D74B9F635C46BAC0D28857206BBCC1FFE4")

    def test_missing_native_client_extra_cannot_pass_sdk_preflight(self):
        tree = ET.parse(client.native.RIG.parent / "runClient.launch")
        entries = next(row for row in tree.getroot().findall("listAttribute")
                       if row.attrib["key"] == "org.eclipse.jdt.launching.CLASSPATH")
        for row in list(entries):
            if "client-extra-26.3-20260918.230105.jar" in row.attrib["value"]:
                entries.remove(row)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "launch.xml"
            tree.write(path, encoding="utf-8")
            with self.assertRaisesRegex(AssertionError, "native vanilla client resources"):
                client.inspect_launch(path)

    def mutate_launch(self, key, transform):
        tree = ET.parse(client.native.RIG.parent / "runClient.launch")
        node = next(row for row in tree.findall("stringAttribute") if row.attrib["key"] == key)
        node.attrib["value"] = transform(node.attrib["value"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "launch.xml"
            tree.write(path, encoding="utf-8")
            with self.assertRaises(AssertionError):
                client.inspect_launch(path)

    def test_agents_classpath_injection_legacy_target_and_connection_arguments_fail(self):
        vm = "org.eclipse.jdt.launching.VM_ARGUMENTS"
        program = "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS"
        for option in (" -javaagent:wrong.jar", " -cp unpinned", " -Dmixin.debug.export=true"):
            self.mutate_launch(vm, lambda value, option=option: value + option)
        for change in (lambda value: value + " --server localhost", lambda value: value.replace("--gameDir .", "--gameDir protected"),
                       lambda value: value.replace("--assetIndex 34", "--assetIndex 29"),
                       lambda value: value.replace("forge_userdev_client", "forge_userdev_server")):
            self.mutate_launch(program, change)

    def test_whole_result_requires_all39_gui_and_all26_gauges_without_promoting_manual_gaps(self):
        report, labels = report_fixture()
        before = copy.deepcopy(report)
        with patch.object(client, "png_record", side_effect=lambda root, name, dims: dict(file=name, dimensions=dims)) as images, \
                patch.object(client, "verify_machine_frame", return_value={}), \
                patch.object(client, "verify_tank_frames"), \
                patch.object(client, "verify_projection_calibration", return_value=dict(status="PASS")), \
                patch.object(client, "verify_world_oil", return_value=dict(status="PASS")), \
                patch.object(client, "read_frame", return_value=(320, 240, b"header-test-only", {})), \
                patch.object(client, "slot_mismatches", return_value=1):
            result = client.validate_result(report, Path("NOT_A_GAME"), labels, {})
            self.assertEqual(416, images.call_count)
            self.assertEqual(1, len(result["manualRequired"]))
            self.assertEqual(before, report)
            changes = (lambda value: value.update(minecraft="1.21.11"),
                       lambda value: value["login"].update(realWorld=False),
                       lambda value: value["login"].update(loggingInEventObserved=False),
                       lambda value: value["login"].update(syntheticComponentBinding=True),
                       lambda value: value["experimentalFixtureGate"].update(actionCount=1),
                       lambda value: value["experimentalFixtureGate"].update(status="AUTO_ACCEPTED"),
                       lambda value: value["child_geometry_context"].update(levelPresent=True),
                       lambda value: value.update(recipe_registry_ids_match_actual_login=False),
                       lambda value: value.update(machine_gui_text_checks=350),
                       lambda value: value.update(machine_gui_negative_controls_rejected=355),
                       lambda value: value["machine_gui_layouts"][0].update(background_extraction_calls=True),
                       lambda value: value["machine_gui_layouts"][0].update(menu_title="Wrong name"),
                       lambda value: value["machine_gui_layouts"].pop(),
                       lambda value: value["tank_gauge_pixels"][0].update(different_pixels=1),
                       lambda value: value["tank_gauge_pixels"].pop(),
                       lambda value: value["fluid_rendering"][0].update(correct_sprite=False),
                       lambda value: value["recipe_book_grouping"].pop(),
                       lambda value: value["scope_results"].pop("oil_renderer"),
                       lambda value: value["child_renderer_geometry"]["cases"][-1].update(status="PASS"))
            for change in changes:
                wrong = copy.deepcopy(report)
                change(wrong)
                with self.assertRaises((AssertionError, KeyError)):
                    client.validate_result(wrong, Path("NOT_A_GAME"), labels, {})

    def test_png_integrity_requires_real_bounded_pixel_stream_not_just_an_ihdr_header(self):
        def chunk(kind, value):
            return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xFFFFFFFF)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            header = chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
            raw = b"\0" + bytes((1, 2, 3, 255)) * 2
            valid = b"\x89PNG\r\n\x1a\n" + header + chunk(b"IDAT", zlib.compress(raw * 2)) + chunk(b"IEND", b"")
            path = root / "unit.png"
            path.write_bytes(valid)
            self.assertEqual(2, client.png_record(root, path.name, (2, 2))["width"])
            for wrong in (valid[:24], valid + b"extra", valid[:-1] + b"x",
                           b"\x89PNG\r\n\x1a\n" + header + chunk(b"IDAT", zlib.compress(b"")) + chunk(b"IEND", b"")):
                path.write_bytes(wrong)
                with self.assertRaises((AssertionError, zlib.error)):
                    client.png_record(root, path.name, (2, 2))


def write_frame(root, name, width, height, data, texts=()):
    """Synthetic framebuffer fixture; no native render or campaign PASS is claimed."""
    def chunk(kind, value):
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xFFFFFFFF)

    rgba = root / (name + ".rgba")
    rgba.write_bytes(data)
    png = root / (name + ".png")
    scanlines = b"".join(b"\0" + data[y * width * 4:(y + 1) * width * 4] for y in range(height))
    png.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
                    + chunk(b"IDAT", zlib.compress(scanlines)) + chunk(b"IEND", b""))
    state = dict(protocol="NATIVE66_GUI_FRAMEBUFFER_FENCED_V1", width=width, height=height,
                 renderer="net.minecraft.client.gui.render.GuiRenderer", extractor="net.minecraft.client.gui.GuiGraphicsExtractor",
                 renderSubmitted=True, fenceCompleted=True, screenshotCallbackCompleted=True, texts=list(texts),
                 blits=[dict(x0=0, y0=12, x1=176, y1=140, color=-1, textureViewIdentity=1,
                             pipeline="minecraft:pipeline/gui_textured", pose=[1, 0, 0, 1, 0, 0], u0=0, v0=0, u1=1, v1=1)])
    native = root / (name + "-native.json")
    native.write_text(json.dumps(state))
    return dict(width=width, height=height, rgba=dict(file=rgba.name, sha256=digest(rgba)),
                png=dict(file=png.name, sha256=digest(png)), native=dict(file=native.name, sha256=digest(native)))


def machine_frame_fixture(root, *, rendered_text=True):
    width, height, left = 320, 240, 72
    slots = [[7 + i % 9 * 18, 157 + i // 9 * 18] for i in range(27)] + [[7 + i * 18, 215] for i in range(9)]
    points = [[x, y, -1 if (x + y) % 2 == 0 else -8355712] for x in range(18) for y in range(18)
              if x in (0, 17) or y in (0, 17)]
    background = bytearray(bytes((20, 30, 40, 255)) * width * height)
    for slot_x, slot_y in slots:
        for x, y, color in points:
            at = ((slot_y + y) * width + left + slot_x + x) * 4
            background[at:at + 4] = bytes((color >> 16 & 255, color >> 8 & 255, color & 255, 255))
    expected = bytearray(background)
    if rendered_text:
        for x0, y0 in ((112, 2), (80, 141)):
            for y in range(y0, y0 + 2):
                for x in range(x0, x0 + 40):
                    at = (y * width + x) * 4
                    expected[at:at + 4] = b"\xff" * 4
    texts = [dict(text=name, x=x, y=y, color=-1, dropShadow=True, pose=[1, 0, 0, 1, 72, 0])
             for name, x, y in (("Unit machine", 40, 1), ("Inventory", 8, 140))]
    actual = write_frame(root, "machine-actual", width, height, expected, texts)
    reference = write_frame(root, "machine-expected", width, height, expected, texts)
    hidden = write_frame(root, "machine-background", width, height, background)
    sources = {"basepower:textures/gui/container/player_inventory.png": "A" * 64,
               "basepower:textures/gui/container/unit.png": "B" * 64}
    proof = dict(viewport=[320, 240, 1], fill=0, left=72, top=0, actual=actual, expected=reference, background=hidden,
                 slot_pixels=dict(border_points=points, slots=slots, checked_pixels=36 * 68,
                     source_sampling="PHYSICAL_PIXEL_CENTERS_ON_256_LOGICAL_UV_V1",
                     logical_texture_size=256, expected_machine_slots=[],
                     slot_border_colors=[[value for _, _, value in points] for _ in slots],
                     inventory_source=dict(resource=list(sources)[0], sha256="A" * 64, width=256, height=256),
                     machine_source=dict(resource=list(sources)[1], sha256="B" * 64, width=256, height=256)))
    return proof, sources


class FramebufferReplayTest(unittest.TestCase):
    def test_world_oil_replay_requires_raw_negative_controls_replaced_atlas_and_real_renderer_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = dict(protocol="NATIVE66_LEVEL_RENDERER_OIL_RELOAD_V1", status="PASS", mipmapLevel=4,
                scope="ACTUAL_LEVEL_RENDERER_NOT_GUI_OR_SYNTHETIC_WORLD", frames={}, pixelControls=[],
                modelsBeforeReload=[], modelsAfterReload=[], nativeGeometryAfterReload=dict(fluid_rendering=[
                    dict(correct_sprite=True, vertices=4) for _ in range(6)]))
            for name in ("original", "empty", "wrong_water", "restored", "after_reload"):
                pixels = bytearray(bytes((40, 50, 60, 255)) * 320 * 240)
                matrix = [.06, 0, 0, 0, 0, .01, 0, 0, 0, .045, .01, 0, 0, 0, 0, 1]
                camera = [512.5, 15.62, 527]
                pools = []
                for index in range(3):
                    fluid = "minecraft:water" if index == 2 or name == "wrong_water" else "minecraft:empty" if name == "empty" else (
                        "basepower:crude_oil" if index == 0 else "basepower:refined_oil")
                    color = (20, 70, 200, 255) if fluid == "minecraft:water" else (230, 230, 230, 255) if fluid == "minecraft:empty" else (
                        (20, 20, 20, 255) if index == 0 else (210, 170, 50, 255))
                    samples = []
                    for x in range(4):
                        for z in range(4):
                            point = [(504, 510, 516)[index] + .75 + x * .75, 6 + 8 / 9, 510 + .75 + z * .75]
                            px, py = client.world_sample_pixel(matrix, camera, point, 320, 240)
                            samples.append(point + [px, py])
                            for dy in range(-1, 2):
                                for dx in range(-1, 2):
                                    at = ((py + dy) * 320 + px + dx) * 4
                                    pixels[at:at + 4] = bytes(color)
                    pools.append(dict(pool=("crude-oil", "refined-oil", "water-control")[index], fluid=fluid,
                        compiledAndVisible=True, samples=samples))
                frame = write_frame(root, "synthetic-world-" + name, 320, 240, pixels)
                frame.pop("native")
                frame.update(renderer="net.minecraft.client.renderer.LevelRenderer", dimension="minecraft:overworld",
                    fenceCompleted=True, hudHidden=True, renderedSections=4, camera=camera,
                    viewRotationProjection=matrix, pools=pools)
                report["frames"][name] = frame
            for actual in ("original", "after_reload"):
                for index, pool in enumerate(("crude-oil", "refined-oil", "water-control")):
                    report["pixelControls"].append(dict(actual=actual, pool=pool, samples=144,
                        emptyDifferent=0 if index == 2 else 144, wrong_waterDifferent=0 if index == 2 else 144))
            for key, offset in (("modelsBeforeReload", 0), ("modelsAfterReload", 10)):
                for index, name in enumerate(("crude_oil", "refined_oil")):
                    report[key].append(dict(fluid="basepower:" + name, layer="TRANSLUCENT",
                        stillSprite=f"basepower:blocks/{name}_still", flowingSprite=f"basepower:blocks/{name}_flow",
                        stillIdentity=1 + index * 2 + offset, flowingIdentity=2 + index * 2 + offset))
            self.assertEqual(8, client.verify_world_oil(root, report)["oilNegativeComparisons"])
            for change in (lambda doc: doc.update(status="FAIL"), lambda doc: doc.update(mipmapLevel=0),
                    lambda doc: doc["frames"]["original"].update(renderer="net.minecraft.client.gui.render.GuiRenderer"),
                    lambda doc: doc["frames"]["original"]["pools"][0].update(compiledAndVisible=False),
                    lambda doc: doc["frames"]["original"]["pools"][0]["samples"][0].__setitem__(3, 10),
                    lambda doc: doc["frames"]["after_reload"].update(camera=[512.5, 15.63, 527]),
                    lambda doc: doc["frames"]["after_reload"].update(rgba=doc["frames"]["empty"]["rgba"]),
                    lambda doc: doc["frames"]["original"].update(rgba=doc["frames"]["wrong_water"]["rgba"]),
                    lambda doc: doc["pixelControls"][0].update(emptyDifferent=0),
                    lambda doc: doc["modelsAfterReload"][0].update(stillIdentity=doc["modelsBeforeReload"][0]["stillIdentity"]),
                    lambda doc: doc["modelsAfterReload"][0].update(stillSprite="minecraft:missingno")):
                wrong = copy.deepcopy(report)
                change(wrong)
                with self.assertRaises(AssertionError):
                    client.verify_world_oil(root, wrong)

    def test_scale2_high_resolution_border_requires_each_physical_sample_not_repeated_center(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proof, _ = machine_frame_fixture(root)
            slots = proof["slot_pixels"]
            slots["slots"].insert(0, [31, 40])
            slots["expected_machine_slots"] = [[31, 40]]
            slots["slot_border_colors"] = [[0xFF123410 + point % 2 * 16 + sample for point in range(68) for sample in range(4)]] + [
                [value for value in row for _ in range(4)] for row in slots["slot_border_colors"]]
            slots["checked_pixels"] = 37 * 68 * 4
            pixels = bytearray(bytes((20, 30, 40, 255)) * 640 * 480)
            for index, (slot_x, slot_y) in enumerate(slots["slots"]):
                for point, (x, y, _) in enumerate(slots["border_points"]):
                    for dy in range(2):
                        for dx in range(2):
                            color = slots["slot_border_colors"][index][point * 4 + dy * 2 + dx]
                            at = (((slot_y + y) * 2 + dy) * 640 + (72 + slot_x + x) * 2 + dx) * 4
                            pixels[at:at + 4] = bytes((color >> 16 & 255, color >> 8 & 255, color & 255, 255))
            frame = (640, 480, pixels, {})
            self.assertEqual(0, client.slot_mismatches(frame, slots, 72, 0, 2))
            wrong = copy.deepcopy(slots)
            wrong["slot_border_colors"][0] = [0xFF123413 + point % 2 * 16 for point in range(68) for _ in range(4)]
            self.assertGreater(client.slot_mismatches(frame, wrong, 72, 0, 2), 0)

    def test_projection_ruler_replays_every_pixel_and_shifted_negative_at_both_scales(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calibration = dict(protocol="NATIVE66_GUI_PROJECTION_PIXEL_RULER_V1", status="PASS", cases=[])
            for scale in (1, 2):
                row = dict(scale=scale, logicalWidth=64, logicalHeight=48, exactPixelsChecked=64 * 48 * scale * scale)
                for key, shift in (("actual", 0), ("shiftedRejected", 1)):
                    pixels = bytearray()
                    for y in range(48 * scale):
                        for x in range(64 * scale):
                            u, v = x // scale, y // scale
                            color = 0xFF37A95C if 7 + shift <= u < 23 + shift and 9 <= v < 25 else (
                                0xFF3158D5 if (u, v) == (2, 2) else 0xFFDD7341 if (u, v) == (63, 47) else 0xFF101820)
                            pixels.extend((color >> 16 & 255, color >> 8 & 255, color & 255, 255))
                    frame = write_frame(root, f"ruler-{scale}-{key}", 64 * scale, 48 * scale, pixels)
                    state_path = root / frame["native"]["file"]
                    state = json.loads(state_path.read_text())
                    state.update(blits=[], fills=[dict(x0=x, y0=y, x1=x + w, y1=y + h, col1=-1, col2=-1,
                        pipeline="minecraft:pipeline/gui", pose=[1, 0, 0, 1, 0, 0])
                        for x, y, w, h in ((7 + shift, 9, 16, 16), (2, 2, 1, 1), (63, 47, 1, 1))])
                    state_path.write_text(json.dumps(state))
                    frame["native"]["sha256"] = digest(state_path)
                    row[key] = frame
                calibration["cases"].append(row)
            self.assertEqual(15360, client.verify_projection_calibration(root, calibration)["physicalPixels"])
            for key in ("actual", "shiftedRejected"):
                wrong = copy.deepcopy(calibration)
                wrong["cases"][1][key] = copy.deepcopy(wrong["cases"][1]["shiftedRejected" if key == "actual" else "actual"])
                with self.assertRaisesRegex(AssertionError, "ruler pixels"):
                    client.verify_projection_calibration(root, wrong)

    def test_custom_gold_machine_border_is_distinct_from_fixed_player_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proof, _ = machine_frame_fixture(root)
            frame = client.read_frame(root, proof["actual"])
            slots = proof["slot_pixels"]
            gold = [0xFFDBAA20 if index % 2 else 0xFF805311 for index in range(68)]
            slots["slots"].insert(0, [31, 40])
            slots["expected_machine_slots"] = [[31, 40]]
            slots["slot_border_colors"].insert(0, gold)
            slots["checked_pixels"] += 68
            pixels = bytearray(frame[2])
            for (x, y, _), color in zip(slots["border_points"], gold):
                at = ((40 + y) * 320 + 72 + 31 + x) * 4
                pixels[at:at + 4] = bytes((color >> 16 & 255, color >> 8 & 255, color & 255, 255))
            self.assertEqual(0, client.slot_mismatches((320, 240, pixels, {}), slots, 72, 0, 1))
            wrong = copy.deepcopy(slots)
            wrong["slot_border_colors"][0] = wrong["slot_border_colors"][-1]
            self.assertGreater(client.slot_mismatches((320, 240, pixels, {}), wrong, 72, 0, 1), 0)
            wrong = copy.deepcopy(slots)
            wrong["slots"][0][0] += 1
            with self.assertRaisesRegex(AssertionError, "GUI declaration"):
                client.slot_mismatches((320, 240, pixels, {}), wrong, 72, 0, 1)

    def test_caption_pixels_and_slot_borders_are_replayed_independently(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proof, sources = machine_frame_fixture(root)
            result = client.verify_machine_frame(root, proof, "Unit machine", "Inventory", sources)
            self.assertEqual(160, result["captionInkPixels"])
            self.assertEqual(36 * 68, result["slotPixels"])
            actual = root / proof["actual"]["rgba"]["file"]
            data = bytearray(actual.read_bytes())
            for x, y, message in ((112, 2, "Caption framebuffer"), (79, 157, "Slot framebuffer")):
                changed = bytearray(data)
                at = (y * 320 + x) * 4
                changed[at:at + 4] = bytes((0, 0, 0, 255))
                actual.write_bytes(changed)
                proof["actual"]["rgba"]["sha256"] = digest(actual)
                with self.assertRaisesRegex(AssertionError, message):
                    client.verify_machine_frame(root, proof, "Unit machine", "Inventory", sources)

    def test_extracted_caption_without_actual_glyph_pixels_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proof, sources = machine_frame_fixture(root, rendered_text=False)
            with self.assertRaisesRegex(AssertionError, "Blank expected caption"):
                client.verify_machine_frame(root, proof, "Unit machine", "Inventory", sources)

    def test_missing_fence_or_native_queue_is_rejected_even_with_valid_png_and_rgba(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frame = write_frame(root, "fence", 2, 2, bytes((1, 2, 3, 255)) * 4)
            path = root / frame["native"]["file"]
            original = json.loads(path.read_text())
            for change in (lambda value: value.update(fenceCompleted=False), lambda value: value.update(blits=[]),
                           lambda value: value["blits"][0].update(textureViewIdentity=0),
                           lambda value: value["blits"][0].update(u0=float("nan"))):
                changed = copy.deepcopy(original)
                change(changed)
                path.write_text(json.dumps(changed))
                frame["native"]["sha256"] = digest(path)
                with self.assertRaises(AssertionError):
                    client.read_frame(root, frame)

    def test_tank_pixels_outside_gauge_and_accepted_garbled_control_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            empty = bytearray(bytes((10, 20, 30, 255)) * 176 * 240)
            expected, wrong = bytearray(empty), bytearray(empty)
            for y in range(66, 81):
                for x in range(80, 96):
                    at = (y * 176 + x) * 4
                    expected[at:at + 4] = bytes((20, 70, 200, 255))
                    wrong[at:at + 4] = bytes((200, 70, 20, 255))
            row = dict(actual_frame=write_frame(root, "tank-actual", 176, 240, expected),
                       expected_frame=write_frame(root, "tank-expected", 176, 240, expected),
                       empty_frame=write_frame(root, "tank-empty", 176, 240, empty),
                       garbled_frame=write_frame(root, "tank-garbled", 176, 240, wrong),
                       source_artwork=dict(resource="basepower:tank.png", sha256="A" * 64, width=256, height=256),
                       render_scale=1,
                       fill_rows=15, whole_panel_different_pixels=0, blank_control_different_pixels=240,
                       garbled_control_different_pixels=240)
            for key in ("actual_frame", "expected_frame"):
                path = root / row[key]["native"]["file"]
                state = json.loads(path.read_text())
                blit = {**state["blits"][0], "x0": 80, "y0": 66, "x1": 96, "y1": 82,
                        "scissor": [80, 66, 16, 15]}
                state["blits"].append(blit)
                path.write_text(json.dumps(state))
                row[key]["native"]["sha256"] = digest(path)
            client.verify_tank_frames(root, row, {"basepower:tank.png": "A" * 64})
            actual = root / row["actual_frame"]["rgba"]["file"]
            changed = bytearray(expected)
            changed[(20 * 176 + 5) * 4] = 255
            actual.write_bytes(changed)
            row["actual_frame"]["rgba"]["sha256"] = digest(actual)
            with self.assertRaisesRegex(AssertionError, "Whole tank"):
                client.verify_tank_frames(root, row, {"basepower:tank.png": "A" * 64})
            actual.write_bytes(expected)
            row["actual_frame"]["rgba"]["sha256"] = digest(actual)
            row["garbled_frame"] = copy.deepcopy(row["expected_frame"])
            with self.assertRaisesRegex(AssertionError, "garbled tank control"):
                client.verify_tank_frames(root, row, {"basepower:tank.png": "A" * 64})
            state_path = root / row["actual_frame"]["native"]["file"]
            state = json.loads(state_path.read_text())
            state["blits"][-1]["y1"] = 81
            state_path.write_text(json.dumps(state))
            row["actual_frame"]["native"]["sha256"] = digest(state_path)
            with self.assertRaisesRegex(AssertionError, "Native fluid extraction"):
                client.verify_tank_frames(root, row, {"basepower:tank.png": "A" * 64})


if __name__ == "__main__":
    unittest.main()
