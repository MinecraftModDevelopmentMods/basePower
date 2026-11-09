"""Read-only JVM evidence before a failed startup is shut down; never a retry."""

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import traceback


def require_label(label):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", label) or label in (".", ".."):
        raise ValueError("Unsafe startup diagnostic label")


def observe(runtime, java, process, label, failure):
    runtime, java = Path(runtime), Path(java)
    require_label(label)
    record = dict(status="STARTUP_FAILURE_NOT_QUALIFIED", runtime=str(runtime), label=label,
                  failureType=type(failure).__name__, failure=str(failure),
                  traceback="".join(traceback.format_exception(failure)),
                  observedUtc=datetime.now(timezone.utc).isoformat(),
                  pid=process.pid, processExitBefore=process.poll(),
                  startupRetry=False, productionMutation=False)
    if record["processExitBefore"] is not None:
        record["threadDumpStatus"] = "PROCESS_ALREADY_EXITED"
        return record
    jcmd = java.with_name("jcmd.exe" if java.suffix.lower() == ".exe" else "jcmd")
    if not jcmd.is_file():
        record["threadDumpStatus"] = "JCMD_UNAVAILABLE"
        return record
    dump = runtime / ("startup-thread-" + label + ".txt")
    if dump.exists():
        raise FileExistsError(dump)
    command = [str(jcmd), str(process.pid), "Thread.print", "-l"]
    record.update(threadDumpCommand=command, threadDumpTimeoutSeconds=20)
    try:
        result = subprocess.run(command, cwd=runtime, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=20,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        payload = result.stdout
        record.update(threadDumpStatus="CAPTURED" if result.returncode == 0 else "JCMD_FAILED",
                      threadDumpExitCode=result.returncode)
    except subprocess.TimeoutExpired as error:
        payload = error.output or b""
        record["threadDumpStatus"] = "JCMD_TIMED_OUT"
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    with dump.open("xb") as stream:
        stream.write(payload)
    record["threadDump"] = str(dump)
    return record


def retain(runtime, label, record, cleanup_failure=None):
    require_label(label)
    path = Path(runtime) / ("startup-failure-" + label + ".json")
    value = dict(record)
    value["cleanupFailure"] = None if cleanup_failure is None else dict(
        type=type(cleanup_failure).__name__, message=str(cleanup_failure),
        traceback="".join(traceback.format_exception(cleanup_failure)))
    value["completedUtc"] = datetime.now(timezone.utc).isoformat()
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
    return path
