"""Exact, code-only Forge seed custody; worlds and configuration are not code."""

import json
import queue
import shutil
import subprocess
import threading
from pathlib import Path
from matrix import Server as HistoricalServer, digest
import native117_startup_diagnostics as startup_diagnostics

INVENTORY = "native117-code-inventory.json"


def code_files(root):
    root = Path(root)
    paths = list((root / "libraries").rglob("*")) + list(root.glob("*.jar"))
    return {path.relative_to(root).as_posix(): {"sha256": digest(path), "size": path.stat().st_size}
            for path in sorted(paths) if path.is_file()}


def verify_code(root, expected=None):
    root = Path(root)
    receipt = root / INVENTORY
    if expected is None:
        expected = json.loads(receipt.read_text(encoding="utf-8"))
    files = expected.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("Missing sealed native117 code inventory")
    if code_files(root) != files:
        raise AssertionError("Native117 server code differs from its exact sealed inventory: " + str(root))
    return expected


def verify_if_sealed(root):
    if (Path(root) / INVENTORY).is_file():
        verify_code(root)


class CodeCustody:
    """Adds pre-boot and post-shutdown checks without changing server commands."""
    def __init__(self, runtime, *args, **kwargs):
        self.code_runtime = Path(runtime)
        verify_if_sealed(self.code_runtime)
        super().__init__(runtime, *args, **kwargs)

    def close(self):
        try:
            return super().close()
        finally:
            verify_if_sealed(self.code_runtime)


class NativeLaunch(HistoricalServer):
    forge_version = "1.17.1-37.1.1"

    def validate_native_log(self, path):
        from native117 import require_clean_log
        require_clean_log(path)

    def __init__(self, runtime, java, launcher, label):
        runtime = Path(runtime)
        self.native_label = label
        self.native_runtime = runtime
        logging_jar = runtime / f"libraries/net/minecraftforge/fmlloader/{self.forge_version}/fmlloader-{self.forge_version}.jar"
        if not logging_jar.is_file(): raise FileNotFoundError("Missing pinned Forge logging configuration")
        logging_configuration = "jar:" + logging_jar.resolve().as_uri() + "!/log4j2.xml"
        self.lines = queue.Queue()
        self.log = (runtime / f"console-{label}.log").open("x", encoding="utf-8")
        self.process = subprocess.Popen(
            [str(java), "-Xms512M", "-Xmx2G", "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
             "-Dlog4j.configurationFile=" + logging_configuration,
             "-Dfml.queryResult=confirm", "@" + str(launcher), "nogui"],
            cwd=runtime, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()
        try:
            self.wait("Done (", 300)
        except BaseException as failure:
            diagnostic = None
            if isinstance(failure, Exception):
                try:
                    self.log.flush()
                    diagnostic = startup_diagnostics.observe(runtime, java, self.process, label, failure)
                except Exception as error:
                    failure.add_note("Startup diagnostic observation failed: " + repr(error))
            cleanup_failure = None
            try:
                self.close()
            except BaseException as error:
                cleanup_failure = error
                failure.add_note("Startup cleanup also failed: " + repr(error))
            if diagnostic is not None:
                try:
                    startup_diagnostics.retain(runtime, label, diagnostic, cleanup_failure)
                except Exception as error:
                    failure.add_note("Startup diagnostic retention failed: " + repr(error))
            raise

    def close(self):
        try:
            return super().close()
        finally:
            for name in ("latest.log", "debug.log"):
                source = self.native_runtime / "logs" / name
                destination = self.native_runtime / f"{name[:-4]}-{self.native_label}.log"
                if source.is_file() and not destination.exists(): shutil.copy2(source, destination)
            for kind in ("console", "latest", "debug"):
                archived = self.native_runtime / f"{kind}-{self.native_label}.log"
                if not archived.is_file():
                    raise AssertionError("Missing native runtime log: " + str(archived))
                self.validate_native_log(archived)


class Server(CodeCustody, NativeLaunch):
    pass
