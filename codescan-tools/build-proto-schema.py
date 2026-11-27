#!/usr/bin/env python3
"""Write proto-schema.json: which field of which protobuf answer is text and which is a nested message.

Usage: build-proto-schema.py [--check]

The scrub step (scrub-recording.py) and the recording check (check-recordings.py) read this file. Without it a
nested message whose bytes are all printable cannot be told apart from text.

Reads the .proto files of sonarlint-core 10.12.1 (git tag 10.12.1.79852, backend/server-api/src/main/proto),
compiles them with protoc (the PROTOC environment variable, or the newest protoc in
~/.m2/repository/com/google/protobuf/protoc) and writes proto-schema.json next to this script.
--check compares with the committed file instead of writing it.
Exit: 0 written or same, 1 different (with --check), 2 git or protoc failed or their output could not be read,
3 protoc or the tag not found.
"""
import glob
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from protowire import read_varint, split_fields  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "proto-schema.json")
TAG = "10.12.1.79852"
PROTO_DIR = "backend/server-api/src/main/proto"

# The message type core parses for each address (backend/server-api, tag above).
ROOTS = {
    "/api/organizations/search.protobuf": "sonarcloud.ws.organizations.SearchWsResponse",
    "/api/components/search.protobuf": "sonarqube.ws.component.SearchWsResponse",
    "/api/components/tree.protobuf": "sonarqube.ws.component.TreeWsResponse",
    "/api/components/show.protobuf": "sonarqube.ws.component.ShowWsResponse",
    "/api/settings/values.protobuf": "sonarqube.ws.settings.ValuesWsResponse",
    "/api/rules/search.protobuf": "sonarqube.ws.rules.SearchResponse",
    "/api/rules/show.protobuf": "sonarqube.ws.rules.ShowResponse",
    "/api/qualityprofiles/search.protobuf": "sonarqube.ws.qualityprofiles.SearchWsResponse",
    "/api/project_branches/list.protobuf": "sonarqube.ws.projectbranch.ListWsResponse",
    "/api/measures/component.protobuf": "sonarqube.ws.measures.ComponentWsResponse",
    "/api/issues/search.protobuf": "sonarqube.ws.issues.SearchWsResponse",
    "/api/hotspots/search.protobuf": "sonarqube.ws.hotspots.SearchWsResponse",
    "/api/hotspots/show.protobuf": "sonarqube.ws.hotspots.ShowWsResponse",
}
# Streams: the first message (parseDelimitedFrom), then every other message (readMessages).
STREAMS = {
    "/api/issues/pull": ["sonarqube.ws.issues.IssuesPullQueryTimestamp", "sonarqube.ws.issues.IssueLite"],
    "/api/issues/pull_taint": ["sonarqube.ws.issues.TaintVulnerabilityPullQueryTimestamp",
                               "sonarqube.ws.issues.TaintVulnerabilityLite"],
    "/api/hotspots/pull": ["sonarqube.ws.hotspots.HotspotPullQueryTimestamp", "sonarqube.ws.hotspots.HotspotLite"],
}
# FieldDescriptorProto.Type
TYPE_GROUP, TYPE_MESSAGE, TYPE_STRING, TYPE_BYTES = 10, 11, 9, 12


def fields_of(buf):
    out = {}
    for tag, wire, payload, raw in split_fields(buf):
        value = payload if wire == 2 else read_varint(raw, len(tag))[0] if wire == 0 else raw[len(tag):]
        out.setdefault(read_varint(tag, 0)[0] >> 3, []).append(value)
    return out


def find_protoc():
    if os.environ.get("PROTOC"):
        return os.environ["PROTOC"]
    found = sorted(glob.glob(os.path.expanduser("~/.m2/repository/com/google/protobuf/protoc/*/protoc-*.exe")),
                   key=lambda p: [int(x) for x in os.path.basename(os.path.dirname(p)).split(".") if x.isdigit()])
    return found[-1] if found else None


def git(*args):
    return subprocess.run(["git", "-C", HERE] + list(args), check=True, capture_output=True).stdout


def descriptor_set(protoc):
    with tempfile.TemporaryDirectory() as tmp:
        names = [n for n in git("ls-tree", "-r", "--full-tree", "--name-only", TAG, PROTO_DIR).decode().split()
                 if n.endswith(".proto")]
        if not names:
            raise ValueError("no .proto files under %s in %s" % (PROTO_DIR, TAG))
        rel = []
        for n in names:
            r = n[len(PROTO_DIR) + 1:]
            os.makedirs(os.path.dirname(os.path.join(tmp, "src", r)), exist_ok=True)
            with open(os.path.join(tmp, "src", r), "wb") as f:
                f.write(git("show", "%s:%s" % (TAG, n)))
            rel.append(r)
        out = os.path.join(tmp, "set.pb")
        subprocess.run([protoc, "-I", os.path.join(tmp, "src"), "--include_imports", "--descriptor_set_out=" + out]
                       + rel, check=True, capture_output=True)
        with open(out, "rb") as f:
            return f.read()


def build_schema(fds):
    messages = {}

    def add(desc, prefix):
        d = fields_of(desc)
        name = prefix + "." + d[1][0].decode()
        table = {}
        for fdesc in d.get(2, []):
            f = fields_of(fdesc)
            number, ftype = f[3][0], f[5][0]
            if ftype == TYPE_GROUP:
                raise ValueError("%s field %d is a group, which this tool does not read" % (name, number))
            if ftype == TYPE_MESSAGE:
                table[str(number)] = ["message", f[6][0].decode().lstrip(".")]
            elif ftype in (TYPE_STRING, TYPE_BYTES):
                table[str(number)] = ["string" if ftype == TYPE_STRING else "bytes", None]
            else:
                table[str(number)] = ["scalar", None]
        messages[name.lstrip(".")] = table
        for nested in d.get(3, []):
            add(nested, name)

    for fdp in fields_of(fds).get(1, []):
        f = fields_of(fdp)
        package = f[2][0].decode() if 2 in f else ""
        for desc in f.get(4, []):
            add(desc, "." + package if package else "")
    for t in list(ROOTS.values()) + [t for pair in STREAMS.values() for t in pair]:
        if t not in messages:
            raise ValueError("message %s is not in the .proto files of %s" % (t, TAG))
    return {"source": "sonarlint-core %s %s" % (TAG, PROTO_DIR),
            "roots": ROOTS, "streams": STREAMS,
            "messages": {k: messages[k] for k in sorted(messages)}}


def render(schema):
    return json.dumps(schema, indent=1, sort_keys=False) + "\n"


def main(argv):
    check = "--check" in argv
    protoc = find_protoc()
    if not protoc or not os.path.exists(protoc):
        print("protoc not found (set PROTOC)")
        return 3
    try:
        git("rev-parse", "--verify", "--quiet", TAG + "^{commit}")
    except subprocess.CalledProcessError:
        print("git tag %s not found" % TAG)
        return 3
    try:
        text = render(build_schema(descriptor_set(protoc)))
    except subprocess.CalledProcessError as e:
        err = (e.stderr or b"").decode("utf-8", "replace").strip()
        print("%s failed (exit %d): %s" % (os.path.basename(str(e.cmd[0])), e.returncode, err or "no error text"))
        return 2
    except (OSError, ValueError) as e:
        print("could not build the schema: %s" % e)
        return 2
    if check:
        try:
            with open(OUT, encoding="utf-8") as f:
                same = f.read() == text
        except FileNotFoundError:
            print("proto-schema.json is missing")
            return 1
        print("proto-schema.json is %s" % ("up to date" if same else "out of date: run build-proto-schema.py"))
        return 0 if same else 1
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print("build-proto-schema: wrote %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
