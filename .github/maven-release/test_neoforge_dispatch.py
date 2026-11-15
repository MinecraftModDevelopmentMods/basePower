"""Offline dispatcher checks; fixture archives never contact a release service."""

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
from zipfile import ZipFile

import verify


class NeoForgeTargetTests(unittest.TestCase):
    def test_native_branch_has_an_explicit_target(self):
        self.assertEqual(("1.21.1", "Java 21"),
                         verify.release_target("master-1.21.1-neo"))
        settings = verify.release_settings("9.9.9.121012",
                                          {"branch": "master-1.21.1-neo"})
        self.assertEqual(("1.21.1", "Java 21", "neoforge", "NeoForge"), settings)

    def test_forge_targets_and_all_existing_records_stay_valid(self):
        manifest = json.loads(verify.MANIFEST.read_text(encoding="utf-8"))
        for version, release in manifest["releases"].items():
            with self.subTest(version=version):
                minecraft, java = verify.release_target(release["branch"])
                self.assertEqual((minecraft, java, "forge", "Forge"),
                                 verify.release_settings(version, release))

    def test_only_the_exact_native_branch_is_accepted(self):
        for branch in ("master-1.21.1-neoforge", "master-1.21.1-Neo",
                       "master-1.21.1-neo-extra", "master-1.21.1-neo\n",
                       "master-1.21.1-neo/", "master-1.21.11-neo",
                       "master-1.20.1-neo", "feature/master-1.21.1-neo", "", None):
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                verify.release_target(branch)

    def test_loader_must_match_the_branch_when_recorded(self):
        for branch, version, expected in (
                ("master-1.21.1-neo", "9.9.9.121012", "neoforge"),
                ("master-1.21.1", "9.9.9.121011", "forge")):
            self.assertEqual(expected, verify.release_settings(
                version, {"branch": branch, "loader": expected})[2])
            for loader in ("fabric", "NeoForge", "forge" if expected == "neoforge"
                           else "neoforge", "", None, 2):
                with self.subTest(branch=branch, loader=loader), self.assertRaises(ValueError):
                    verify.release_settings(version, {"branch": branch, "loader": loader})

    def test_version_code_must_match_minecraft_and_loader(self):
        for branch, versions in {
            "master-1.21.1-neo": ("9.9.9.121011", "9.9.9.121013", "9.9.9.121112",
                                 "9.9.9.0121012", "9.9.9.121012+Neo",
                                 "9.9.9.121012\n", "9.9.121012", None),
            "master-1.21.1": ("9.9.9.121012", "9.9.9.121111"),
            "master-1.10.2": ("9.9.9.121012", "9.9.9.110022"),
        }.items():
            for version in versions:
                with self.subTest(branch=branch, version=version), self.assertRaises(ValueError):
                    verify.release_settings(version, {"branch": branch})

    def test_target_cli_is_offline_and_emits_loader_metadata(self):
        version = "9.9.9.121012"
        manifest = {"releases": {version: {"branch": "master-1.21.1-neo"}}}
        output = io.StringIO()
        with patch.object(verify.sys, "argv", ["verify.py", "target", version, "unused"]), \
                patch.object(verify, "MANIFEST") as source, \
                patch.object(verify, "github") as github, \
                patch.object(verify, "read_url") as read_url, \
                patch.object(verify, "stage") as stage, contextlib.redirect_stdout(output):
            source.read_text.return_value = json.dumps(manifest)
            verify.main()
        self.assertEqual("minecraft=1.21.1\njava=Java 21\nloader=neoforge\n"
                         "loader_display=NeoForge\n", output.getvalue())
        github.assert_not_called()
        read_url.assert_not_called()
        stage.assert_not_called()

    def test_unlisted_native_version_is_not_approved_by_the_route(self):
        with patch.object(verify.sys, "argv", ["verify.py", "target", "9.9.9.121012", "unused"]), \
                self.assertRaisesRegex(ValueError, "Version is not approved"):
            verify.main()

    def test_invalid_record_is_rejected_before_staging(self):
        version = "9.9.9.121011"
        manifest = {"releases": {version: {"branch": "master-1.21.1-neo"}}}
        with patch.object(verify.sys, "argv", ["verify.py", "stage-candidate", version, "unused"]), \
                patch.object(verify, "MANIFEST") as source, patch.object(verify, "stage") as stage, \
                self.assertRaises(ValueError):
            source.read_text.return_value = json.dumps(manifest)
            verify.main()
        stage.assert_not_called()


class SealedArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name) / "release"
        self.manifest = json.loads(verify.MANIFEST.read_text(encoding="utf-8"))
        self.version = "9.9.9.121012"
        self.release = {"branch": "master-1.21.1-neo", "loader": "neoforge",
                        "commit": "a" * 40, "tag": self.version, "runId": 123,
                        "artifactId": 456, "curseFileId": 789}
        self.jars = verify.jar_names(self.manifest, self.version)
        self.payload = {name: ("fixture:" + name).encode("ascii") for name in self.jars}
        self.ci = {"head_sha": self.release["commit"], "conclusion": "success",
                   "path": ".github/workflows/ci.yml", "event": "push",
                   "head_branch": self.release["branch"]}
        self.tag = {"sha": self.release["commit"]}
        self.details = {"workflow_run": {"id": 123}, "expired": False}
        self.make_archive()

    def make_archive(self, paths=("build/release/SHA256SUMS",), extra_sums="",
                     changed_jar=False):
        sums = "".join(verify.digest(data) + "  " + name + "\n"
                       for name, data in self.payload.items()) + extra_sums
        output = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(output, "w") as bundle:
                for path in paths:
                    bundle.writestr(path, sums)
                for name, data in self.payload.items():
                    bundle.writestr("build/libs/" + name, data + (b"changed" if changed_jar else b""))
                bundle.writestr("CHANGELOG.txt", self.version + "\n\nChecked fixture.\n")
        self.archive = output.getvalue()
        self.release["archiveSha256"] = verify.digest(self.archive)
        self.release["mainSha256"] = verify.digest(self.payload[self.jars[0]])
        self.details["digest"] = "sha256:" + self.release["archiveSha256"]

    def stage(self, candidate=True):
        repo = self.manifest["repository"]
        responses = {
            f"repos/{repo}/actions/runs/123": self.ci,
            f"repos/{repo}/commits/{self.release['tag']}": self.tag,
            f"repos/{repo}/actions/artifacts/456": self.details,
        }

        def download(args, stdout, check):
            self.assertEqual(["gh", "api", f"repos/{repo}/actions/artifacts/456/zip"], args)
            self.assertTrue(check)
            stdout.write(self.archive)
            return subprocess.CompletedProcess(args, 0)

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": repo}), \
                patch.object(verify, "github", side_effect=lambda path: json.dumps(responses[path])), \
                patch.object(verify.subprocess, "run", side_effect=download), \
                patch.object(verify, "read_url") as network, \
                patch.object(verify, "verify_curseforge") as curseforge:
            verify.stage(self.manifest, self.version, self.release, self.work, candidate=candidate)
            network.assert_not_called()
            return curseforge

    def test_native_archive_stages_only_exact_sealed_jars(self):
        self.stage()
        for name, data in self.payload.items():
            self.assertEqual(data, (self.work / name).read_bytes())
        self.assertEqual({name: verify.digest(data) for name, data in self.payload.items()},
                         json.loads((self.work / "verified.json").read_text()))
        self.assertEqual(self.version + "\n\nChecked fixture.\n",
                         (self.work / "release-notes.txt").read_text())

    def test_forge_archive_retains_the_canonical_checksum_path(self):
        self.version = "9.9.9.121011"
        self.release.update(branch="master-1.21.1", loader="forge", tag=self.version)
        self.ci["head_branch"] = self.release["branch"]
        self.jars = verify.jar_names(self.manifest, self.version)
        self.payload = {name: name.encode("ascii") for name in self.jars}
        self.make_archive()
        self.stage()
        self.assertEqual(self.payload[self.jars[0]], (self.work / self.jars[0]).read_bytes())

    def test_libs_checksum_path_is_not_a_fallback(self):
        self.make_archive(paths=("build/libs/SHA256SUMS",))
        with self.assertRaisesRegex(ValueError, "checksum path"):
            self.stage()

    def test_parallel_checksum_paths_are_ambiguous(self):
        self.make_archive(paths=("build/release/SHA256SUMS", "build/libs/SHA256SUMS"))
        with self.assertRaisesRegex(ValueError, "checksum path"):
            self.stage()

    def test_duplicate_canonical_checksum_member_is_rejected(self):
        self.make_archive(paths=("build/release/SHA256SUMS",) * 2)
        with self.assertRaisesRegex(ValueError, "checksum path"):
            self.stage()

    def test_alternate_checksum_file_is_not_accepted_alongside_canonical(self):
        self.make_archive(paths=("build/release/SHA256SUMS", "build/libs/SHA256SUMS.txt"))
        with self.assertRaisesRegex(ValueError, "checksum path"):
            self.stage()

    def test_ci_identity_and_tag_must_still_match(self):
        original = copy.deepcopy(self.ci)
        for field, value in (("head_sha", "b" * 40), ("conclusion", "failure"),
                             ("path", ".github/workflows/other.yml"),
                             ("event", "pull_request"), ("head_branch", "master-1.21.1")):
            self.ci = dict(original, **{field: value})
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "CI provenance changed"):
                self.stage()
        self.ci = original
        self.tag["sha"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "Release tag moved"):
            self.stage()
        self.assertFalse(self.work.exists())

    def test_candidate_requires_the_exact_tag(self):
        self.release["tag"] = "another-tag"
        with self.assertRaisesRegex(ValueError, "exact release tag"):
            self.stage()
        self.assertFalse(self.work.exists())

    def test_artifact_identity_digest_and_expiry_are_required(self):
        original = copy.deepcopy(self.details)
        for change in ({"workflow_run": {"id": 999}}, {"digest": "sha256:" + "0" * 64},
                       {"expired": True}):
            self.details = dict(original, **change)
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "checksum-sealed"):
                self.stage()
        self.assertFalse(self.work.exists())

    def test_archive_bytes_must_match_the_recorded_hash(self):
        self.archive += b"changed"
        with self.assertRaisesRegex(ValueError, "CI archive hash changed"):
            self.stage()

    def test_main_jar_hash_must_match_the_approved_record(self):
        self.release["mainSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "approved release"):
            self.stage()

    def test_jar_bytes_must_match_the_checksum_file(self):
        self.make_archive(changed_jar=True)
        with self.assertRaisesRegex(ValueError, "Bad CI checksum"):
            self.stage()

    def test_exactly_three_jars_must_be_sealed(self):
        self.make_archive(extra_sums="0" * 64 + "  unexpected.jar\n")
        with self.assertRaisesRegex(ValueError, "exactly the three release jars"):
            self.stage()

    def test_existing_stage_directory_is_never_overwritten(self):
        self.work.mkdir()
        sentinel = self.work / "keep.txt"
        sentinel.write_bytes(b"keep")
        with self.assertRaises(FileExistsError):
            self.stage()
        self.assertEqual(b"keep", sentinel.read_bytes())

    def test_existing_curseforge_release_still_uses_its_file_id(self):
        curseforge = self.stage(candidate=False)
        curseforge.assert_called_once_with(self.manifest, self.version, self.release, 789)


class WorkflowContractTests(unittest.TestCase):
    def test_native_metadata_is_used_and_forge_relations_are_unchanged(self):
        manifest = json.loads(verify.MANIFEST.read_text(encoding="utf-8"))
        workflow = (Path(__file__).resolve().parent.parent / "workflows/deploy-release.yml").read_text()
        self.assertIn("loaders: ${{ steps.preflight.outputs.loader }}", workflow)
        self.assertIn("(${{ steps.preflight.outputs.loader_display }})", workflow)
        block = workflow.split("curseforge-dependencies: |\n", 1)[1].split("          fail-mode:", 1)[0]
        relations = [line.strip() for line in block.splitlines()]
        self.assertEqual(
            ["240967(required)", "245586(required)", "243157(optional)"]
            if manifest["artifactId"] == "basePower" else
            ["1780140(required)", "240967(required)"], relations[:-1])
        self.assertEqual("${{ steps.preflight.outputs.loader == 'neoforge' && "
                         "'261744(required)' || '' }}", relations[-1])

    def test_default_branch_resume_and_no_overwrite_guards_remain(self):
        manifest = json.loads(verify.MANIFEST.read_text(encoding="utf-8"))
        workflow = (Path(__file__).resolve().parent.parent / "workflows/deploy-release.yml").read_text()
        for marker in ("github.repository == '" + manifest["repository"] + "'",
                       "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)",
                       "default: check-only", "cancel-in-progress: false",
                       "inputs.mode == 'publish' && inputs.existing_curseforge_file_id == ''",
                       "inputs.mode == 'publish' && steps.preflight.outputs.state == 'absent'",
                       "Do not retry an upload.", '${EXISTING_CF_FILE:-$UPLOADED_CF_FILE}',
                       'require-absent "$RELEASE_VERSION" "$RELEASE_WORK"', "--verify-tag"):
            self.assertIn(marker, workflow)


if __name__ == "__main__":
    unittest.main()
