"""Check an approved release without rebuilding it or uploading to CurseForge."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree
from zipfile import ZipFile


MAVEN = "https://maven.moddev.zone/releases"
MANIFEST = Path(__file__).with_name("releases.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_url(url, allow_absent=False):
    try:
        request = Request(url, headers={"User-Agent": "MMD-Maven-Release/1.0", "Cache-Control": "no-cache"})
        with urlopen(request, timeout=30) as response:
            require(response.status == 200, f"Unexpected HTTP status for {url}")
            return response.read()
    except HTTPError as error:
        if error.code == 404 and allow_absent:
            return None
        raise


def github(path):
    return subprocess.check_output(["gh", "api", path])


def stage(manifest, version, release, work):
    repo = manifest["repository"]
    require(os.environ.get("GITHUB_REPOSITORY", repo) == repo, "Wrong source repository")
    artifact_id = release["artifactId"]
    ci = json.loads(github(f"repos/{repo}/actions/runs/{release['runId']}"))
    require(ci["head_sha"] == release["commit"] and ci["conclusion"] == "success"
            and ci["path"] == ".github/workflows/ci.yml" and ci["event"] == "push"
            and ci["head_branch"] == release["branch"], "CI provenance changed")
    if release.get("tag"):
        ref = json.loads(github(f"repos/{repo}/commits/{release['tag']}"))
        require(ref["sha"] == release["commit"], "Release tag moved")
    details = json.loads(github(f"repos/{repo}/actions/artifacts/{artifact_id}"))
    require(details["workflow_run"]["id"] == release["runId"]
            and details["digest"] == "sha256:" + release["archiveSha256"]
            and not details["expired"], "Expected unexpired, checksum-sealed CI artifact")
    work.mkdir(parents=True, exist_ok=False)
    archive = work / "ci.zip"
    with archive.open("xb") as out:
        subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}/zip"], stdout=out, check=True)
    require(digest(archive.read_bytes()) == release["archiveSha256"], "CI archive hash changed")

    jars = jar_names(manifest, version)
    with ZipFile(archive) as bundle:
        sums = {}
        for line in bundle.read("build/release/SHA256SUMS").decode("ascii").splitlines():
            sha, name = line.split(maxsplit=1)
            require(name not in sums and re.fullmatch(r"[a-fA-F0-9]{64}", sha), "Malformed CI checksums")
            sums[name] = sha.lower()
        require(set(sums) == set(jars), "CI did not seal exactly the three release jars")
        require(sums[jars[0]] == release["mainSha256"], "CI main jar does not match the published release")
        for name in jars:
            member = "build/libs/" + name
            require(bundle.namelist().count(member) == 1, f"Missing or duplicate CI jar: {name}")
            data = bundle.read(member)
            require(digest(data) == sums[name], f"Bad CI checksum: {name}")
            (work / name).write_bytes(data)
    file_id = release["curseFileId"]
    public = read_url(f"https://edge.forgecdn.net/files/{file_id // 1000}/{file_id % 1000}/{jars[0]}")
    require(digest(public) == release["mainSha256"], "CurseForge bytes changed")
    (work / "verified.json").write_text(json.dumps(sums), encoding="ascii")
    print(f"CurseForge file {file_id} matches {jars[0]}: {release['mainSha256']}")


def jar_names(manifest, version):
    stem = manifest["artifactId"] + "-" + version
    return [stem + suffix for suffix in (".jar", "-sources.jar", "-javadoc.jar")]


def pom_digest(data):
    # Gradle writes native line endings. Jar checks always use the exact bytes.
    return digest(data.replace(b"\r\n", b"\n"))


def destination_state(manifest, version, release, work, verify_metadata=False):
    jars = jar_names(manifest, version)
    sums = json.loads((work / "verified.json").read_text(encoding="ascii"))
    require(sums[jars[0]] == release["mainSha256"], "Prepared release does not match the manifest")
    pom = jars[0].removesuffix(".jar") + ".pom"
    sums[pom] = pom_digest((work / "gradle/publications/mavenJava/pom-default.xml").read_bytes())
    directory = f"{MAVEN}/{manifest['groupId'].replace('.', '/')}/{manifest['artifactId']}"
    present = 0
    for name, sha in sums.items():
        if name in jars:
            require(digest((work / name).read_bytes()) == sha, f"Prepared file changed: {name}")
        remote = read_url(f"{directory}/{version}/{name}", allow_absent=True)
        if remote is not None:
            actual = pom_digest(remote) if name == pom else digest(remote)
            require(actual == sha, f"Maven already contains different bytes: {name}")
            present += 1
    if verify_metadata:
        # After a successful write, a public cache can expose only part of the release.
        # This path only reads; preflight still refuses to write to a partial version.
        if present != len(sums):
            return "pending"
        document = read_url(f"{directory}/maven-metadata.xml", allow_absent=True)
        if document is None or version not in [
            node.text for node in ElementTree.fromstring(document).findall("versioning/versions/version")
        ]:
            return "pending"
    else:
        require(present in (0, len(sums)), "Maven version is partially populated; refusing automatic overwrite")
    return "present" if present else "absent"


def main():
    mode, version, folder = sys.argv[1:]
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    require(version in manifest["releases"], f"Version is not approved: {version}")
    release, work = manifest["releases"][version], Path(folder)
    if mode == "stage":
        stage(manifest, version, release, work)
    elif mode in ("check", "require-absent"):
        state = destination_state(manifest, version, release, work)
        require(mode == "check" or state == "absent", "Destination is not empty")
        print(state)
    elif mode == "verify":
        for delay in (0, 2, 5, 10, 20, 30):
            time.sleep(delay)
            if destination_state(manifest, version, release, work, verify_metadata=True) == "present":
                print(f"MMD Maven verified: {manifest['groupId']}:{manifest['artifactId']}:{version}")
                print(f"Main jar SHA-256 matches CurseForge: {release['mainSha256']}")
                return
        raise ValueError("Publication is not publicly readable")
    else:
        raise ValueError(f"Unknown mode: {mode}")


if __name__ == "__main__":
    main()
