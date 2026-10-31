"""Use the unchanged Steam control oracle with the native 1.17 validator."""

import argparse
import hashlib
import json
from pathlib import Path

import native117_machine_operations
from native117_actor_io import bind
import steam_control_audit as historical


def native(line):
    historical.require(line == '117', 'Unsupported Minecraft line')
    return native117_machine_operations


audit_documents = bind(historical.audit_documents, native=native)
_audit = bind(historical.audit, native=native, audit_documents=audit_documents)


def audit(runtime, line='117'):
    result = _audit(runtime, line)
    result['adapterSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest().upper()
    result['adapterScope'] = 'Native validator selection only; historical Steam comparison code unchanged'
    return result


def __getattr__(name):
    return getattr(historical, name)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--line', choices=('117',), default='117')
    args = parser.parse_args(argv)
    try:
        result = audit(args.runtime, args.line)
    except Exception as error:
        result = dict(status='FAIL', line=args.line, runtime=str(args.runtime), failures=[str(error)])
    print(json.dumps(result, indent=2, sort_keys=True, allow_nan=False))
    return int(result['status'] != 'PASS')


if __name__ == '__main__':
    raise SystemExit(main())
