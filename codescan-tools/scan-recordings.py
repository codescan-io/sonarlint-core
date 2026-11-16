#!/usr/bin/env python3
"""Look for secrets that must not be in committed recordings.

Usage: scan-recordings.py <recordings dir or a single file> [hidden-values list]

Every file under the folder is read, whatever its type (mappings, __files/ bodies, anything else). A single file
(for example the docs page) is read as plain text.

Real values come from CODESCAN_TOKEN, CODESCAN_ORG and CODESCAN_PROJECT (if set) and from the list the probe
wrote with --hidden-list (one value per line: user names, other organisations and projects, server id, login
hosts). leakfind.py says in which forms they are looked for (URL encoded, JSON \\u escapes, base64, any letter
case, inside protobuf bodies). Without them only the generic patterns run, and a SKIP line says so.
Exit 0 if clean, 1 if not.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import leakfind  # noqa: E402

GENERIC = [
    re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    re.compile(r"Bearer\s+(?=[A-Za-z0-9._-]*[0-9._-])[A-Za-z0-9._-]{8,}"),
    re.compile(r"Basic\s+(?=[A-Za-z0-9+/=]*[0-9+/=])[A-Za-z0-9+/=]{8,}"),
    re.compile(r"\b(sq[pua]_[0-9a-f]{20,}|[0-9a-f]{40})\b"),
]
ALLOWED = {"user@example.test", "Bearer REDACTED", "Basic REDACTED"}  # what the scrub step writes
PLUGIN_BUILD = re.compile(r'(implementationBuild\\*"\s*:\s*\\*")[0-9a-f]{40}')


def load_secrets(hidden_path):
    secrets = [s for s in (os.environ.get("CODESCAN_TOKEN"), os.environ.get("CODESCAN_ORG"),
                           os.environ.get("CODESCAN_PROJECT")) if s]
    if not secrets:
        print("SKIP: CODESCAN_TOKEN / CODESCAN_ORG / CODESCAN_PROJECT not set, so real values are not compared "
              "(generic patterns still run)")
    if hidden_path:
        if os.path.isfile(hidden_path):
            with open(hidden_path, encoding="utf-8") as f:
                hidden = [line.strip() for line in f if line.strip()]
            if not hidden:
                print("SKIP: hidden-values list %s is empty" % hidden_path)
            secrets += hidden
        else:
            print("SKIP: no hidden-values list at %s (run the probe with --hidden-list to create it)" % hidden_path)
    return secrets


def generic_hits(texts):
    found = []
    for text in texts:
        text = PLUGIN_BUILD.sub(r"\1", text)
        for rx in GENERIC:
            for hit in rx.findall(text):
                hit = hit if isinstance(hit, str) else hit[0]
                why = "matches pattern %s" % rx.pattern[:30]
                if hit not in ALLOWED and why not in found:
                    found.append(why)
    return found


def candidate_files(path):
    if os.path.isfile(path):
        yield os.path.basename(path), path
        return
    for root, dirs, files in os.walk(path):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(root, name)
            yield os.path.relpath(full, path), full


def scan(target, secrets):
    finder = leakfind.Finder(secrets)
    single = os.path.isfile(target)
    bad = []
    seen = 0
    for shown, path in candidate_files(target):
        seen += 1
        with open(path, "rb") as f:
            raw = f.read()
        views = leakfind.Views().add_file(raw, is_json=not single and path.endswith(".json"))
        for why in views.errors + finder.find(views) + generic_hits(views.plain):
            bad.append((shown, why))
    if seen == 0:
        bad.append((target, "no recording files to scan"))
    return bad


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 64
    secrets = load_secrets(argv[2] if len(argv) > 2 else None)
    bad = scan(argv[1], secrets)
    for name, why in bad:
        print("LEAK", name, why)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
