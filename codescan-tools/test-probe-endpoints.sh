#!/usr/bin/env bash
# Tests for probe-endpoints.sh and scrub-recording.py.
# Run: codescan-tools/test-probe-endpoints.sh
# Needs: bash, curl, python3. Uses a throw-away local fake server; never talks to a real CodeScan server.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECORDINGS_DIR="$HERE/../medium-tests/src/test/resources/codescan-recordings"
PROBE="$HERE/probe-endpoints.sh"
SCRUB="$HERE/scrub-recording.py"
TMP="$(mktemp -d)"
FAKE_PID=""
cleanup() { [ -n "$FAKE_PID" ] && kill "$FAKE_PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  PASS: $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL: $1"; }
check() { # check "<name>" <command...>
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi
}
not_contains() { [ -e "$2" ] && ! grep -rqF -- "$1" "$2"; }
not_contains_word() { [ -e "$2" ] && ! grep -rqwF -- "$1" "$2"; }
report_scan() { # report_scan <exit code> <scan output> [what]: a scan that ran without the real values is not a PASS
  local what="${3:-any recording}"
  if [ "$1" -ne 0 ]; then bad "scan of $what failed (exit $1)"; grep -v '^SKIP' "$2" | sed 's/^/    | /' | head -20
  elif grep -q '^SKIP' "$2"; then
    grep '^SKIP' "$2" | sed 's/^/    /'
    echo "  NOTE: real-value comparison NOT run (real values not loaded); only the generic patterns were checked"
  else ok "no real token / org / project / hidden value / email / auth header in $what"; fi
}
find_docs_page() { # find_docs_page <start folder>: the nearest docs/qa/codescan-endpoints.md above it, or nothing
  local d="$1"
  while [ -n "$d" ] && [ "$d" != "/" ]; do
    if [ -f "$d/docs/qa/codescan-endpoints.md" ]; then echo "$d/docs/qa/codescan-endpoints.md"; return; fi
    d="$(dirname "$d")"
  done
}

FAKE_TOKEN="FAKETOKEN-abc123XYZ"
FAKE_ORG="fake-org-name"
FAKE_PROJECT="fake-project-key"
FAKE_EMAIL="jane.doe@example.com"
FAKE_USER="jdoe42"

echo "== 1. scrub step removes token, organisation, project, email and user names"

mkdir -p "$TMP/in" "$TMP/out"
cat > "$TMP/in/sample.json" <<EOF
{
  "request": {
    "method": "GET",
    "urlPath": "/api/components/search",
    "queryParameters": {
      "organization": { "equalTo": "$FAKE_ORG" },
      "project": { "equalTo": "$FAKE_PROJECT" }
    },
    "headers": { "Authorization": { "equalTo": "Bearer $FAKE_TOKEN" } }
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json", "X-Echo": "Bearer $FAKE_TOKEN" },
    "jsonBody": {
      "organization": "$FAKE_ORG",
      "components": [ { "key": "$FAKE_PROJECT:src/Main.java", "author": "$FAKE_EMAIL", "assignee": "$FAKE_USER" } ],
      "url": "https://staging.example/dashboard?id=$FAKE_PROJECT&organization=$FAKE_ORG&token=$FAKE_TOKEN"
    }
  }
}
EOF
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  CODESCAN_SCRUB_USERS="$FAKE_USER" python3 -I "$SCRUB" "$TMP/in" "$TMP/out"
check "scrub exits 0 and writes the file" test -s "$TMP/out/sample.json"
check "token does not survive"    not_contains "$FAKE_TOKEN"   "$TMP/out"
check "organisation does not survive" not_contains "$FAKE_ORG" "$TMP/out"
check "project does not survive"  not_contains "$FAKE_PROJECT" "$TMP/out"
check "email does not survive"    not_contains "$FAKE_EMAIL"   "$TMP/out"
check "user name does not survive" not_contains "$FAKE_USER"   "$TMP/out"
check "output is still valid JSON" python3 -I -c 'import json,sys; json.load(open(sys.argv[1]))' "$TMP/out/sample.json"
check "organisation became test-org" grep -q 'test-org' "$TMP/out/sample.json"
check "project became test-project"  grep -q 'test-project' "$TMP/out/sample.json"

echo "== 1b. scrub step keeps protobuf bodies valid (length prefixes fixed)"

cat > "$TMP/proto_check.py" <<'EOF'
import base64, json, sys

def varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]; i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7

def enc_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)

def field(num, wire, payload):
    return enc_varint((num << 3) | wire) + payload

def ld(num, data):
    return field(num, 2, enc_varint(len(data)) + data)

def parse(buf):
    """Strict parse. Returns list of (field, wire, value); raises on bad length."""
    i, out = 0, []
    while i < len(buf):
        tag, i = varint(buf, i)
        num, wire = tag >> 3, tag & 7
        if wire == 0:
            v, i = varint(buf, i)
        elif wire == 2:
            n, i = varint(buf, i)
            if i + n > len(buf):
                raise ValueError("length runs past end")
            v, i = buf[i:i + n], i + n
        elif wire == 1:
            v, i = buf[i:i + 8], i + 8
        elif wire == 5:
            v, i = buf[i:i + 4], i + 4
        else:
            raise ValueError("bad wire type")
        out.append((num, wire, v))
    return out

def build_sample(org, project):
    inner = ld(1, org.encode()) + field(2, 0, enc_varint(5)) + ld(3, (project + ":src/A.java").encode())
    return ld(1, inner) + ld(2, b"plain name") + field(3, 0, enc_varint(300))

TEXT = b"z see acme-corp-key in dashboard!!"   # printable text that also happens to parse as a protobuf field

if sys.argv[1] == "make-text":
    body = ld(1, ld(1, b"sonar.x") + ld(2, TEXT)) + ld(1, ld(1, b"tail"))
    m = {"request": {"method": "GET", "urlPath": "/api/settings/values.protobuf"},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(sys.argv[2], "w"))
elif sys.argv[1] == "verify-text":
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    top = parse(body)
    setting = parse(top[0][2])
    assert setting[1][2] == b"z see test-org in dashboard!!", setting[1][2]
    assert parse(top[1][2])[0][2] == b"tail"
elif sys.argv[1] == "make":
    org, project, path = sys.argv[2:5]
    body = build_sample(org, project)
    m = {"request": {"method": "GET", "urlPath": "/api/x.protobuf"},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(path, "w"))
elif sys.argv[1] == "verify":
    path = sys.argv[2]
    body = base64.b64decode(json.load(open(path))["response"]["base64Body"])
    top = parse(body)                       # raises if lengths are wrong
    inner = parse(top[0][2])
    assert inner[0][2] == b"test-org", inner[0][2]
    assert inner[1][2] == 5, inner[1]
    assert inner[2][2] == b"test-project:src/A.java", inner[2][2]
    assert top[1][2] == b"plain name"
    assert top[2][2] == 300
    # body must not carry the original text in any form
    for secret in sys.argv[3:]:
        assert secret.encode() not in body, secret
EOF
mkdir -p "$TMP/pin" "$TMP/pout"
python3 -I "$TMP/proto_check.py" make "$FAKE_ORG" "$FAKE_PROJECT" "$TMP/pin/proto.json"
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  python3 -I "$SCRUB" "$TMP/pin" "$TMP/pout"
check "binary body: org+project replaced, lengths consistent" \
  python3 -I "$TMP/proto_check.py" verify "$TMP/pout/proto.json" "$FAKE_ORG" "$FAKE_PROJECT"

# Printable text that merely looks like a protobuf field must be treated as text, not rewritten as a message.
mkdir -p "$TMP/tin" "$TMP/tout"
python3 -I "$TMP/proto_check.py" make-text "$TMP/tin/text.json"
CODESCAN_TEST_ORG="acme-corp-key" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  python3 -I "$SCRUB" "$TMP/tin" "$TMP/tout"
check "printable text that looks like protobuf is replaced as plain text" \
  python3 -I "$TMP/proto_check.py" verify-text "$TMP/tout/text.json"

# A body with nothing to scrub must come out byte-identical.
mkdir -p "$TMP/qin" "$TMP/qout"
python3 -I "$TMP/proto_check.py" make "other" "other2" "$TMP/qin/clean.json"
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  python3 -I "$SCRUB" "$TMP/qin" "$TMP/qout"
check "binary body with nothing to scrub is unchanged" \
  python3 -I -c 'import json,sys; a=json.load(open(sys.argv[1])); b=json.load(open(sys.argv[2])); sys.exit(0 if a["response"]["base64Body"]==b["response"]["base64Body"] else 1)' \
  "$TMP/qin/clean.json" "$TMP/qout/clean.json"

echo "== 1c. scrub step: streams of length-prefixed messages, short user names, refuses output that does not parse"

cat >> "$TMP/proto_check.py" <<'EOF'
elif sys.argv[1] == "make-stream":
    # four length-prefixed messages (like api/hotspots/pull); message 3 holds the org, a 130-byte string and a user
    org, project, user, urlpath, path = sys.argv[2:7]
    msgs = [field(1, 0, enc_varint(1234567890123)),
            ld(1, b"plain") + ld(2, (project + ":src/A.cls").encode()),
            ld(1, org.encode()) + ld(2, b"x" * 130 + user.encode()),
            ld(1, b"tail")]
    body = b"".join(enc_varint(len(m)) + m for m in msgs)
    m = {"request": {"method": "GET", "urlPath": urlpath},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(path, "w"))
elif sys.argv[1] == "verify-stream":
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    msgs, i = [], 0
    while i < len(body):
        n, i = varint(body, i)
        assert i + n <= len(body), "length prefix runs past the end"
        parse(body[i:i + n])               
        msgs.append(body[i:i + n]); i += n
    assert len(msgs) == 4, len(msgs)
    m2, m3 = parse(msgs[1]), parse(msgs[2])
    assert m2[1][2] == b"test-project:src/A.cls", m2
    assert m3[0][2] == b"test-org", m3[0]
    assert m3[1][2] == b"x" * 130 + b"user-1", m3[1][2][-12:]
    assert parse(msgs[3])[0][2] == b"tail"
    for secret in sys.argv[3:]:
        assert secret.encode() not in body, secret
elif sys.argv[1] == "make-garbage":
    # not a stream of messages: the first length runs past the end of the body
    m = {"request": {"method": "GET", "urlPath": sys.argv[3]},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(b"\x7f" + sys.argv[2].encode()).decode()}}
    json.dump(m, open(sys.argv[4], "w"))
elif sys.argv[1] == "make-short":
    body = ld(1, b"bo") + ld(5, b"robot") + ld(8, b"bo-bo")
    m = {"request": {"method": "GET", "urlPath": "/api/hotspots/show.protobuf"},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(sys.argv[2], "w"))
    t = {"request": {"method": "GET", "urlPath": "/api/hotspots/show"},
         "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                      "body": '{"assignee":"bo","note":"a robot said bo-bo","other":"bo"}'}}
    json.dump(t, open(sys.argv[3], "w"))
elif sys.argv[1] == "verify-short":
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    top = parse(body)
    assert top[0][2] == b"user-short-1", top[0][2]       # whole field equal to the short login: replaced
    assert top[1][2] == b"robot", top[1][2]              # "bo" inside another word: left alone
    assert top[2][2] == b"bo-bo", top[2][2]
    text = json.load(open(sys.argv[3]))["response"]["body"]
    assert '"assignee":"user-short-1"' in text.replace(" ", ""), text
    assert "a robot said bo-bo" in text, text
EOF
mkdir -p "$TMP/sin" "$TMP/sout" "$TMP/gin" "$TMP/gout" "$TMP/hin" "$TMP/hout"
python3 -I "$TMP/proto_check.py" make-stream "$FAKE_ORG" "$FAKE_PROJECT" "$FAKE_USER" /api/hotspots/pull "$TMP/sin/stream.json"
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  CODESCAN_SCRUB_USERS="$FAKE_USER" python3 -I "$SCRUB" "$TMP/sin" "$TMP/sout"
check "stream of 4 messages: every length prefix is right, replacement inside message 3" \
  python3 -I "$TMP/proto_check.py" verify-stream "$TMP/sout/stream.json" "$FAKE_ORG" "$FAKE_PROJECT" "$FAKE_USER"
python3 -I "$TMP/proto_check.py" make-garbage "$FAKE_ORG" /api/issues/pull "$TMP/gin/garbage.json"
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  python3 -I "$SCRUB" "$TMP/gin" "$TMP/gout" > "$TMP/garbage.out" 2>&1
GARBAGE_RC=$?
[ "$GARBAGE_RC" -ne 0 ] && ok "a stream that does not parse: scrub exits non-zero" || bad "a stream that does not parse: scrub exits non-zero (got $GARBAGE_RC)"
check "a stream that does not parse: no output file is left" bash -c "! ls '$TMP/gout'/*.json >/dev/null 2>&1"
check "a stream that does not parse: the error says why" grep -qi 'stream\|message\|length' "$TMP/garbage.out"
python3 -I "$TMP/proto_check.py" make-short "$TMP/hin/short.json" "$TMP/hin/short-json.json"
CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN" \
  CODESCAN_SCRUB_EXACT="bo" python3 -I "$SCRUB" "$TMP/hin" "$TMP/hout"
check "short user name: replaced where it is the whole value, not inside other words" \
  python3 -I "$TMP/proto_check.py" verify-short "$TMP/hout/short.json" "$TMP/hout/short-json.json"
# the stream check (check-recordings.py) must catch a corrupt stream, and pass the scrubbed one
mkdir -p "$TMP/badstream"
python3 -I - "$TMP/sin/stream.json" "$TMP/badstream/41-hotspots-pull.json" <<'EOF'
import base64, json, sys
m = json.load(open(sys.argv[1]))
b = bytearray(base64.b64decode(m["response"]["base64Body"]))
b[0] += 3                                    # the first length prefix is now wrong
m["response"]["base64Body"] = base64.b64encode(bytes(b)).decode()
json.dump(m, open(sys.argv[2], "w"))
EOF
check "stream check passes on the scrubbed stream" python3 -I "$HERE/check-recordings.py" "$TMP/sout" streams
check "stream check fails on a stream with a wrong length prefix" bash -c "! python3 -I '$HERE/check-recordings.py' '$TMP/badstream' streams"
# every length prefix right, but a field core reads as a nested message (HotspotLite.textRange = 8) is not one
mkdir -p "$TMP/badnested"
python3 -I - "$TMP/sin/stream.json" "$TMP/badnested/41-hotspots-pull.json" <<'EOF'
import base64, json, sys
m = json.load(open(sys.argv[1]))
first = b"\x08\x01"                         # HotspotPullQueryTimestamp.queryTimestamp = 1
lite = b"\x0a\x03AYx" + b"\x42\x03\xff\xff\xff"  # key, then textRange holding 3 bytes that are no message
body = bytes([len(first)]) + first + bytes([len(lite)]) + lite
m["request"]["urlPath"] = "/api/hotspots/pull"
m["request"].pop("url", None)
m["response"]["base64Body"] = base64.b64encode(body).decode()
json.dump(m, open(sys.argv[2], "w"))
EOF
check "stream check fails when a nested message inside a stream does not parse" bash -c "! python3 -I '$HERE/check-recordings.py' '$TMP/badnested' streams"

echo "== 1d. scrub step: nested messages follow core's .proto files; values written another way"

cat >> "$TMP/proto_check.py" <<'EOF'
elif sys.argv[1] == "make-nested":
    # issues/search answer: paging (binary) + one Issue whose bytes are all printable, so the Issue also reads as text
    org, urlpath, path = sys.argv[2:5]
    component = (org + ":force-app/main/default/classes/PrintableName.cls").encode()
    issue = ld(4, component)
    assert 32 <= len(component) < 127 and 32 <= len(issue) < 127 and issue.decode().isprintable()
    body = ld(4, field(1, 0, enc_varint(1)) + field(3, 0, enc_varint(1))) + ld(6, issue)
    m = {"request": {"method": "GET", "urlPath": urlpath},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(path, "w"))
elif sys.argv[1] == "verify-nested":
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    top = parse(body)
    issue = parse(top[1][2])                # raises if the Issue's own length prefix is wrong
    assert issue == [(4, 2, b"test-org:force-app/main/default/classes/PrintableName.cls")], issue
elif sys.argv[1] == "make-multiline":
    # a rule description (string field) with a newline that also reads as a protobuf field: it is text
    org, path = sys.argv[2:4]
    rest = ("see " + org + " for the whole rule text here").encode()
    desc = b"\n" + bytes([len(rest)]) + rest
    rule = ld(1, b"apex:S1") + ld(2, b"Rule name") + ld(5, desc)
    body = ld(1, rule)
    m = {"request": {"method": "GET", "urlPath": "/api/rules/show.protobuf"},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(path, "w"))
elif sys.argv[1] == "verify-multiline":
    org = sys.argv[3]
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    rule = parse(parse(body)[0][2])
    rest = ("see " + org + " for the whole rule text here").encode()
    want = b"\n" + bytes([len(rest)]) + rest.replace(org.encode(), b"test-org")
    assert rule[2][2] == want, rule[2][2]
elif sys.argv[1] == "make-numberfield":
    # issues/pull: IssueLite field 2 is a number in the schema, but this answer holds text there
    user, path = sys.argv[2:4]
    msgs = [field(1, 0, enc_varint(1700000000000)), ld(1, b"AYx-key") + ld(2, user.encode())]
    body = b"".join(enc_varint(len(m)) + m for m in msgs)
    m = {"request": {"method": "GET", "urlPath": "/api/issues/pull"},
         "response": {"status": 200, "headers": {"Content-Type": "application/x-protobuf"},
                      "base64Body": base64.b64encode(body).decode()}}
    json.dump(m, open(path, "w"))
elif sys.argv[1] == "verify-numberfield":
    body = base64.b64decode(json.load(open(sys.argv[2]))["response"]["base64Body"])
    n, i = varint(body, 0)
    n2, j = varint(body, i + n)
    assert parse(body[j:j + n2]) == [(1, 2, b"AYx-key"), (2, 2, b"user-1")], parse(body[j:j + n2])
EOF
python3 -I "$HERE/build-proto-schema.py" --check > "$TMP/schema.out" 2>&1
SCHEMA_RC=$?
case "$SCHEMA_RC" in
  0) ok "proto-schema.json matches core 10.12.1's .proto files" ;;
  3) echo "    NOT RUN: proto-schema.json comparison (protoc or tag 10.12.1.79852 not found): $(head -1 "$TMP/schema.out")" ;;
  *) bad "proto-schema.json does not match core 10.12.1's .proto files (exit $SCHEMA_RC)"; tail -5 "$TMP/schema.out" ;;
esac
# a tool that breaks (here protoc) must not look like "out of date" (exit 1), and must say what broke
printf '#!/bin/sh\necho "protoc: simulated failure" >&2\nexit 7\n' > "$TMP/badprotoc"; chmod +x "$TMP/badprotoc"
PROTOC="$TMP/badprotoc" python3 -I "$HERE/build-proto-schema.py" --check > "$TMP/schema-broken.out" 2>&1
BROKEN_RC=$?
[ "$BROKEN_RC" -eq 2 ] && ok "schema check: a broken protoc gives exit 2, not 'out of date'" \
  || bad "schema check: a broken protoc gives exit 2, not 'out of date' (got $BROKEN_RC)"
check "schema check: a broken protoc is named with its own error text" grep -q 'protoc.*simulated failure' "$TMP/schema-broken.out"
check "schema check: a broken protoc gives no Python traceback" bash -c "! grep -q Traceback '$TMP/schema-broken.out'"
SCRUB_ENV=(CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" CODESCAN_TEST_TOKEN="$FAKE_TOKEN")
mkdir -p "$TMP/nin" "$TMP/nout" "$TMP/uin" "$TMP/uout" "$TMP/min" "$TMP/mout"
python3 -I "$TMP/proto_check.py" make-nested "$FAKE_ORG" /api/issues/search.protobuf "$TMP/nin/nested.json"
env "${SCRUB_ENV[@]}" python3 -I "$SCRUB" "$TMP/nin" "$TMP/nout" > "$TMP/nested.out" 2>&1
check "a nested message whose bytes are all printable: replaced inside, its own length prefix stays right" \
  python3 -I "$TMP/proto_check.py" verify-nested "$TMP/nout/nested.json"
python3 -I "$TMP/proto_check.py" make-nested "$FAKE_ORG" /api/not-in-the-schema.protobuf "$TMP/uin/nested.json"
env "${SCRUB_ENV[@]}" python3 -I "$SCRUB" "$TMP/uin" "$TMP/uout" > "$TMP/unknown.out" 2>&1
UNKNOWN_RC=$?
[ "$UNKNOWN_RC" -ne 0 ] && ok "text-or-message bytes with no schema: scrub exits non-zero" || bad "text-or-message bytes with no schema: scrub exits non-zero (got $UNKNOWN_RC)"
check "text-or-message bytes with no schema: no output file is left" bash -c "! ls '$TMP/uout'/*.json >/dev/null 2>&1"
check "text-or-message bytes with no schema: the error says why" grep -qi 'schema' "$TMP/unknown.out"
python3 -I "$TMP/proto_check.py" make-multiline "$FAKE_ORG" "$TMP/min/multi.json"
env "${SCRUB_ENV[@]}" python3 -I "$SCRUB" "$TMP/min" "$TMP/mout" > /dev/null 2>&1
check "a string field with a newline that reads like a message is replaced as text" \
  python3 -I "$TMP/proto_check.py" verify-multiline "$TMP/mout/multi.json" "$FAKE_ORG"
mkdir -p "$TMP/numin" "$TMP/numout"
python3 -I "$TMP/proto_check.py" make-numberfield "assignee-person-5" "$TMP/numin/num.json"
env "${SCRUB_ENV[@]}" CODESCAN_SCRUB_USERS="assignee-person-5" python3 -I "$SCRUB" "$TMP/numin" "$TMP/numout" > /dev/null 2>&1
check "text where the schema expects a number is still scrubbed" \
  python3 -I "$TMP/proto_check.py" verify-numberfield "$TMP/numout/num.json"

NAME_NA="José Pérez"
mkdir -p "$TMP/ein" "$TMP/eout" "$TMP/b64in" "$TMP/b64out"
python3 -I - "$TMP/ein" "$NAME_NA" <<'PYEOF'
import json, sys
d, name = sys.argv[1], sys.argv[2]
def text(n, body):
    json.dump({"request": {"method": "GET", "urlPath": "/api/" + n},
               "response": {"status": 200, "headers": {"Content-Type": "application/json"}, "body": body}},
              open("%s/%s.json" % (d, n), "w"))
text("escaped", json.dumps({"name": name}))                     # JSON \u escapes inside the body text
text("upper", json.dumps({"name": name.upper()}, ensure_ascii=False))
PYEOF
env "${SCRUB_ENV[@]}" CODESCAN_SCRUB_USERS="$NAME_NA" python3 -I "$SCRUB" "$TMP/ein" "$TMP/eout" > /dev/null 2>&1
for n in escaped upper; do
  check "scrub replaces a user name written as $n" python3 -I -c '
import json, sys, unicodedata
body = json.load(open(sys.argv[1]))["response"]["body"]
got = json.loads(body)["name"]
sys.exit(0 if got == "user-1" else 1)' "$TMP/eout/$n.json"
done
python3 -I - "$TMP/b64in/b64.json" "$NAME_NA" <<'PYEOF'
import base64, json, sys
body = base64.b64encode(("said " + sys.argv[2]).encode()).decode()
json.dump({"request": {"method": "GET", "urlPath": "/api/b64"},
           "response": {"status": 200, "headers": {"Content-Type": "text/plain"}, "body": body}}, open(sys.argv[1], "w"))
PYEOF
env "${SCRUB_ENV[@]}" CODESCAN_SCRUB_USERS="$NAME_NA" python3 -I "$SCRUB" "$TMP/b64in" "$TMP/b64out" > "$TMP/b64.out" 2>&1
B64_RC=$?
[ "$B64_RC" -ne 0 ] && ok "final check: a user name left in base64 form stops the scrub" || bad "final check: a user name left in base64 form stops the scrub (got $B64_RC)"
check "final check: nothing is written when a value is left" bash -c "! ls '$TMP/b64out'/*.json >/dev/null 2>&1"

echo "== 2. probe script: masks the token, picks the auth style, fails when it cannot work"

cat > "$TMP/fake_server.py" <<'EOF'
import http.server, json, sys, threading, urllib.parse

mode = sys.argv[1]          # bearer | basic-only | reject-all
portfile = sys.argv[2]
logfile = sys.argv[3]
token = sys.argv[4]
import base64

def vi(n):
    out = bytearray()
    while True:
        b = n & 127; n >>= 7
        out.append(b | (128 if n else 0))
        if not n:
            return bytes(out)

def ld(num, data):
    return vi((num << 3) | 2) + vi(len(data)) + data

def stream():
    # four length-prefixed messages; message 3 holds the project key and the second user
    msgs = [b"\x08" + vi(1700000000000), ld(1, b"plain"), ld(1, b"fake-project-key") + ld(2, b"assignee-person-5"), ld(1, b"tail")]
    return b"".join(vi(len(m)) + m for m in msgs)

def url_path_of(p):
    return urllib.parse.urlsplit(p).path

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass
    def _do(self):
        with open(logfile, "a") as f:
            f.write(self.command + " " + self.path + "\n")
        auth = self.headers.get("Authorization", "")
        if mode == "drop-tree" and url_path_of(self.path) == "/api/components/tree.protobuf":
            return  # no answer at all: the connection is closed (a call that fails mid-run)
        ok = (mode in ("bearer", "drop-tree") and auth == "Bearer " + token) or \
             (mode == "basic-only" and auth == "Basic " + base64.b64encode((token + ":").encode()).decode())
        url = urllib.parse.urlsplit(self.path)
        q = dict(urllib.parse.parse_qsl(url.query))
        with open(logfile, "a") as f:
            f.write("AUTH " + ("good" if ok else "bad") + " " + url.path + "\n")
        ctype = "application/json"
        status = 200
        if not ok:
            body = b'{"errors":[{"msg":"Unauthorized"}]}'
            status = 401
        elif q.get("component") == "no-such-project-for-tests" or q.get("project") == "no-such-project-for-tests":
            body = b'{"errors":[{"msg":"Component key not found"}]}'
            status = 404
        elif url.path in ("/api/issues/search", "/api/issues/search.protobuf"):
            body = json.dumps({"issues": [{"key": "ISSUE-1", "assignee": "assignee-person-5", "author": "assignee-person-5"}],
                               "users": [{"login": "assignee-person-5", "name": "Second Person"}],
                               "comments": [{"login": "commenter-31", "markdown": "ok"}],
                               "paging": {"total": 1}}).encode()
        elif url.path in ("/api/hotspots/show", "/api/hotspots/show.protobuf"):
            body = json.dumps({"key": "HS-1", "author": {"login": "bo", "name": "Bo Short"},
                               "comment": [{"login": "commenter-31", "markdown": "seen"}]}).encode()
        elif url.path in ("/api/hotspots/search", "/api/hotspots/search.protobuf"):
            body = json.dumps({"hotspots": [{"key": "HS-1", "author": "bo", "assignee": "assignee-person-5"}],
                               "paging": {"total": 1}}).encode()
        elif url.path in ("/api/issues/pull", "/api/hotspots/pull", "/api/issues/pull_taint"):
            body = stream()
            ctype = "application/x-protobuf"
        elif url.path in ("/api/components/search", "/api/components/search.protobuf") and q.get("ps") == "500":
            page = int(q.get("p", "1"))
            comp = {"key": "OTHERPROJ-key-x", "name": "Other Proj Display Name"} if page == 1 else \
                   {"key": "SECONDPAGE-proj-y", "name": "Second Page Name"}
            body = json.dumps({"components": [comp], "paging": {"total": 501, "pageSize": 500, "pageIndex": page}}).encode()
        elif url.path == "/api/users/current":
            body = json.dumps({"login": "fake-login-9", "name": "Fake Person Name"}).encode()
        elif url.path == "/api/organizations/search":
            body = json.dumps({"organizations": [{"key": "fake-org-name", "name": "Fake Org"},
                                                 {"key": "otherorg-key-zz", "name": "Other Org Display"}]}).encode()
        elif url.path == "/api/qualityprofiles/search":
            langs = ["java", "cobol", "py", "ts", "js", "sfmeta", "vf", "sf", "web", "go", "kotlin", "ruby"]
            profs = [{"key": "KEY-java-2", "language": "java", "isDefault": False}]
            profs += [{"key": "KEY-" + l, "language": l, "isDefault": True} for l in langs]
            body = json.dumps({"profiles": profs}).encode()
        elif url.path == "/api/rules/search.protobuf" and "qprofile" in q:
            total = 1100 if q["qprofile"] == "KEY-java" else 10
            vi = lambda n: bytes([n]) if n < 128 else bytes([(n & 127) | 128, n >> 7])
            body = b"\x08" + vi(total) + b"\x10" + vi(int(q.get("p", "1"))) + b"\x18" + vi(500)
            ctype = "application/x-protobuf"
        elif url.path == "/api/plugins/installed":
            body = json.dumps({"plugins": [{"key": "codescan", "sonarLintSupported": True},
                                           {"key": "codescanlang", "sonarLintSupported": True},
                                           {"key": "serveronly", "sonarLintSupported": False}]}).encode()
        elif url.path == "/api/plugins/download":
            body = b"JUNKJUNK" * 12
            ctype = "application/java-archive"
            status = 206
        elif url.path == "/api/settings/values" and not q:
            body = json.dumps({"settings": [{"key": "sonar.auth.oidc.url",
                                             "value": "https://login.fake-idp.example-corp.net/oauth"}]}).encode()
        elif url.path == "/batch/issues":
            body = b'<html><body><a href="https://sso.fake-sso-corp.example.net/login">Log in</a></body></html>'
            ctype = "text/html;charset=UTF-8"
        elif url.path == "/api/push/sonarlint_events":
            body = b'{"errors":[{"msg":"Insufficient privileges"}]}'
            status = 403
        else:
            doc = {"contact": "dev.person@fake-mail-corp.test", "pad": "", "components": [{"key": "OTHERPROJ-key-x", "name": "Other Proj Display Name"}],
                   "echo": auth, "status": "UP", "version": "24.12", "issues": [], "id": "FAKE-SERVER-ID-777"}
            doc["pad"] = "x" * max(0, 100 - json.dumps(doc).index("OTHERPROJ-key-x"))
            body = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_GET = _do
    do_POST = _do
    do_PUT = _do
    do_DELETE = _do

srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
open(portfile, "w").write(str(srv.server_address[1]))
srv.serve_forever()
EOF

start_fake() { # start_fake <mode>; sets PORT, FAKE_PID
  rm -f "$TMP/port" "$TMP/requests.log"; : > "$TMP/requests.log"
  python3 -I "$TMP/fake_server.py" "$1" "$TMP/port" "$TMP/requests.log" "$FAKE_TOKEN" &
  FAKE_PID=$!
  for _ in $(seq 1 50); do [ -s "$TMP/port" ] && break; sleep 0.1; done
  PORT="$(cat "$TMP/port")"
}
stop_fake() { kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""; }
run_probe() { # run_probe <url> [extra args]; output in $TMP/probe.out, exit code in PROBE_RC
  local url="$1"; shift
  CODESCAN_TEST_URL="$url" CODESCAN_TEST_ORG="$FAKE_ORG" CODESCAN_TEST_PROJECT="$FAKE_PROJECT" \
    CODESCAN_TEST_TOKEN="$FAKE_TOKEN" PROBE_CONNECT_TIMEOUT=1 PROBE_MAX_TIME=3 \
    bash "$PROBE" "$@" > "$TMP/probe.out" 2>&1
  PROBE_RC=$?
}

# 2a. Bearer works. The fake server echoes the Authorization header in every body.
cat > "$TMP/mapq.py" <<'EOF'
import json, os, sys
d, path, expr = sys.argv[1:4]
for n in sorted(os.listdir(d)):
    if n.endswith(".json"):
        m = json.load(open(os.path.join(d, n)))
        if m["request"]["urlPath"] == path and eval(expr, {"m": m, "req": m["request"], "res": m["response"],
                                                            "qp": m["request"].get("queryParameters", {})}):
            sys.exit(0)
sys.exit(1)
EOF
mq() { python3 -I "$TMP/mapq.py" "$TMP/rec" "$@"; }
mkdir -p "$TMP/rec"
echo '{}' > "$TMP/rec/99-stale.json"; echo 'keep me' > "$TMP/rec/notes.txt"

start_fake bearer
run_probe "http://127.0.0.1:$PORT" --record "$TMP/rec" --table "$TMP/table.md" --hidden-list "$TMP/hidden.txt"
[ "$PROBE_RC" -eq 0 ] && ok "bearer server: exit 0" || { bad "bearer server: exit 0 (got $PROBE_RC)"; sed 's/^/    | /' "$TMP/probe.out" | head -20; }
check "token is not in the probe output (server echoed it)" not_contains "$FAKE_TOKEN" "$TMP/probe.out"
check "probe output says bearer auth" grep -qi 'auth.*bearer' "$TMP/probe.out"
check "probe output shows the masked token marker" grep -q '\*\*\*' "$TMP/probe.out"
check "probe made only GET requests" bash -c "! grep -v '^GET \|^AUTH ' '$TMP/requests.log' | grep -q ."
check "probe called several addresses" bash -c "[ \$(grep -c '^GET ' '$TMP/requests.log') -ge 10 ]"
check "recordings were written" bash -c "ls '$TMP/rec'/*.json >/dev/null 2>&1"
check "token is not in recordings (server echoed it)" not_contains "$FAKE_TOKEN" "$TMP/rec"
check "organisation is not in recordings" not_contains "$FAKE_ORG" "$TMP/rec"
check "project is not in recordings" not_contains "$FAKE_PROJECT" "$TMP/rec"
check "other project key is not in recordings" not_contains "OTHERPROJ-key-x" "$TMP/rec"
check "other project name is not in recordings" not_contains "Other Proj Display Name" "$TMP/rec"
check "server id is not in recordings" not_contains "FAKE-SERVER-ID-777" "$TMP/rec"
check "other project key is not in the table, not even cut short" not_contains "OTHERPROJ" "$TMP/table.md"
check "server id is not in the table" not_contains "FAKE-SERVER-ID-777" "$TMP/table.md"
check "table has no blank line inside it" bash -c "! sed -n '/^| #/,\$p' '$TMP/table.md' | grep -q '^\$'"

# every page of every recorded rules-by-profile call (the fake java profile has 1100 rules, 500 per page)
for pg in 1 2 3; do
  check "rules of the java profile: page $pg is recorded" mq /api/rules/search.protobuf \
    "qp.get('qprofile',{}).get('equalTo')=='KEY-java' and qp['p']['equalTo']=='$pg'"
done
check "rules of the java profile: no page 4" bash -c "! python3 -I '$TMP/mapq.py' '$TMP/rec' /api/rules/search.protobuf \"qp.get('qprofile',{}).get('equalTo')=='KEY-java' and qp['p']['equalTo']=='4'\""
# CodeScan language keys: sf, vf, sfmeta (not apex)
for l in sf vf sfmeta js ts py java web go kotlin ruby; do
  check "profile $l: rules are requested" grep -q "qprofile=KEY-$l&" "$TMP/requests.log"
  check "profile $l: rules are recorded" mq /api/rules/search.protobuf \
    "qp.get('qprofile',{}).get('equalTo')=='KEY-$l' and qp['p']['equalTo']=='1'"
done
check "the default java profile is used, not the other one" bash -c "! grep -q 'qprofile=KEY-java-2' '$TMP/requests.log'"
check "more than 7 profiles are recorded (no cap)" bash -c "[ \$(grep -c 'GET /api/rules/search.protobuf?qprofile=.*&p=1' '$TMP/requests.log') -ge 11 ]"
check "a profile of a language outside the list is not requested" bash -c "! grep -q 'qprofile=KEY-cobol' '$TMP/requests.log'"
check "pull request names the CodeScan Apex key sf, vf and sfmeta" bash -c "grep 'GET /api/issues/pull?' '$TMP/requests.log' | grep -q 'sf,vf,sfmeta'"
check "pull request does not use the key apex" bash -c "! grep 'GET /api/issues/pull?' '$TMP/requests.log' | grep -q 'apex'"
# real answers that core must cope with
check "batch/issues is asked with key and branch" grep -q 'GET /batch/issues?key=.*&branch=' "$TMP/requests.log"
check "batch/issues 200 HTML answer is recorded with a note" mq /batch/issues \
  "res['status']==200 and 'text/html' in res['headers']['Content-Type'] and 'real server behaviour' in m['metadata']['note'].lower() and qp['branch']['equalTo']=='main'"
check "push events 403 answer is recorded, note says SonarQube-type only, no languages pin" mq /api/push/sonarlint_events \
  "res['status']==403 and 'sonarqube-type' in m['metadata']['note'].lower() and 'not called' in m['metadata']['note'].lower() and 'languages' not in qp"
# matching rules
check "developers/search_events matches any from" mq /api/developers/search_events \
  "'matches' in qp['from'] and 'equalTo' not in qp['from']"
for path in /api/issues/pull /api/issues/pull_taint /api/hotspots/pull; do
  check "$path: no languages match, note about SonarCloud connections" mq "$path" \
    "'languages' not in qp and 'SonarCloud' in m['metadata']['note']"
done
check "hotspots/search note explains the missing files parameter" mq /api/hotspots/search.protobuf \
  "'files' in m['metadata']['note']"
# plugin downloads: tiny stand-in jars for the plugins core would fetch
JAR_EXPR="__import__('zipfile').is_zipfile(__import__('io').BytesIO(__import__('base64').b64decode(res['base64Body'])))"
for k in codescan codescanlang; do
  check "plugin $k: stand-in jar mapping" mq /api/plugins/download \
    "qp['plugin']['equalTo']=='$k' and res['status']==200 and res['headers']['Content-Type']=='application/java-archive' and 'stand-in' in m['metadata']['note'].lower() and $JAR_EXPR"
done
check "a plugin that is not for SonarLint is not downloaded" bash -c "! grep -q 'plugin=serveronly' '$TMP/requests.log'"
# the addresses must be the ones core builds
TAINT_ORDER="roslyn.sonaranalyzer.security.cs,javasecurity,jssecurity,phpsecurity,pythonsecurity,tssecurity"
check "taint rules: repositories in core's order (SonarLanguage enum)" grep -qF "repositories=$TAINT_ORDER&" "$TMP/requests.log"
check "taint rules: recording does not pin the repositories" mq /api/rules/search.protobuf \
  "'repositories' in qp and 'equalTo' not in qp['repositories']"
check "taint vulnerabilities: asked with an empty rules list (no taint rules on the server)" grep -q 'GET /api/issues/search.protobuf?statuses=.*&rules=&branch=' "$TMP/requests.log"
check "taint vulnerabilities: recording matches any rules value" mq /api/issues/search.protobuf \
  "'rules' in qp and 'equalTo' not in qp['rules'] and qp.get('types',{}).get('equalTo')=='VULNERABILITY'"
check "new code: asked without a branch" bash -c "grep 'GET /api/measures/component.protobuf' '$TMP/requests.log' | grep -q 'additionalFields=periods' && ! grep 'GET /api/measures/component.protobuf' '$TMP/requests.log' | grep -q 'branch='"
check "new code: periods recording has no branch parameter" mq /api/measures/component.protobuf \
  "qp.get('additionalFields',{}).get('equalTo')=='periods' and 'branch' not in qp"
check "new code: period (SonarQube kind) recording has no branch parameter" mq /api/measures/component.protobuf \
  "qp.get('additionalFields',{}).get('equalTo')=='period' and 'branch' not in qp"
check "batch/issues recording matches any key (core sends project:path for one file)" mq /batch/issues \
  "'matches' in qp.get('key',{}) and qp.get('branch',{}).get('equalTo')=='main' and res['status']==200"
check "sources/raw recording does not pin the branch (core asks with and without)" mq /api/sources/raw "'branch' not in qp"
check "sources/raw fallback: any key, worse priority, marked as fallback, says it is a stand-in" mq /api/sources/raw \
  "'matches' in qp.get('key',{}) and m.get('priority',5)>5 and m['metadata'].get('fallback') is True and 'stand-in' in m['metadata']['note'].lower() and res['status']==200"
check "sources/raw fallback: no extra request is sent for it" bash -c "[ \$(grep -c 'GET /api/sources/raw' '$TMP/requests.log') -eq 1 ]"
check "made-up token: asked once with a bad token" grep -q '^AUTH bad /api/authentication/validate' "$TMP/requests.log"
check "made-up token: 401 recorded, matched on the made-up token, better priority" mq /api/authentication/validate \
  "res['status']==401 and req.get('headers',{}).get('Authorization',{}).get('equalTo')=='Bearer REDACTED' and m.get('priority',5)<5"
check "the real token is never sent as the made-up one" bash -c "! grep -q 'Bearer $FAKE_TOKEN' '$TMP/probe.out'"
check "unknown organisation: recorded" mq /api/organizations/search.protobuf "qp.get('organizations',{}).get('equalTo')=='no-such-org-for-tests'"
check "unknown project: components/show 404 recorded" mq /api/components/show.protobuf \
  "qp['component']['equalTo']=='no-such-project-for-tests' and res['status']==404"
check "unknown project: qualityprofiles/search 404 recorded" mq /api/qualityprofiles/search.protobuf \
  "qp['project']['equalTo']=='no-such-project-for-tests' and res['status']==404"
# second user (assignee, author, comment writer) found in the answers themselves, long and short names
for v in assignee-person-5 "Second Person" commenter-31 bo "Bo Short" SECONDPAGE-proj-y "Second Page Name"; do
  check "hidden list holds: $v" grep -qxF -- "$v" "$TMP/hidden.txt"
  if [ "${#v}" -lt 4 ]; then NC=not_contains_word; else NC=not_contains; fi
  check "not in recordings: $v" $NC "$v" "$TMP/rec"
  check "not in probe output: $v" $NC "$v" "$TMP/probe.out"
done
check "short user name is replaced only as a whole value in the recordings" bash -c "! grep -rq '\"bo\"' '$TMP/rec'"
check "other projects are read page by page (page 2 asked)" grep -q 'GET /api/components/search?qualifiers=TRK&organization=.*&ps=500&p=2' "$TMP/requests.log"
check "e-mail address is not in the probe output" not_contains "dev.person@fake-mail-corp.test" "$TMP/probe.out"
check "e-mail address is not in the table" not_contains "dev.person@fake-mail-corp.test" "$TMP/table.md"
check "e-mail address is not in the recordings" not_contains "dev.person@fake-mail-corp.test" "$TMP/rec"
for n in features-list server-version orgs-json components-search-json search-projects settings-global settings-json \
         rules-json-clean-code project-branches-json qprofiles-json tree-json issues-json hotspots-search-json; do
  check "table: $n is marked not called by core" bash -c "grep -- '-$n |' '$TMP/table.md' | grep -q 'not called by core'"
done
check "table: a row core does call is not marked" bash -c "! grep -- '-orgs-by-key |' '$TMP/table.md' | grep -q 'not called by core'"
check "recorded pull streams parse (every message, every length prefix)" python3 -I "$HERE/check-recordings.py" "$TMP/rec" streams
check "recorded hotspots/pull: message 3 holds test-project, the second user is gone" python3 -I - "$TMP/rec" <<'PYEOF'
import base64, glob, json, sys
for f in glob.glob(sys.argv[1] + "/*.json"):
    m = json.load(open(f))
    if m["request"]["urlPath"] == "/api/hotspots/pull":
        b = base64.b64decode(m["response"]["base64Body"])
        assert b"test-project" in b and b"assignee-person-5" not in b and b"fake-project-key" not in b
        sys.exit(0)
sys.exit(1)
PYEOF
# smaller points
check "other organisations are read with ps=500" grep -q 'GET /api/organizations/search?member=true&ps=500' "$TMP/requests.log"
check "stale numbered recording is removed" test ! -e "$TMP/rec/99-stale.json"
check "other files in the recordings folder are kept" test -e "$TMP/rec/notes.txt"
# hidden-values list written by the probe, and login hosts found without being told
check "hidden list exists and is private (mode 600)" bash -c "[ \"\$(stat -f %Lp '$TMP/hidden.txt' 2>/dev/null || stat -c %a '$TMP/hidden.txt')\" = 600 ]"
for v in fake-login-9 "Fake Person Name" otherorg-key-zz FAKE-SERVER-ID-777 OTHERPROJ-key-x login.fake-idp.example-corp.net sso.fake-sso-corp.example.net; do
  check "hidden list holds: $v" grep -qxF -- "$v" "$TMP/hidden.txt"
  check "not in recordings: $v" not_contains "$v" "$TMP/rec"
  check "not in probe output: $v" not_contains "$v" "$TMP/probe.out"
done
check "hidden list does not hold the token" not_contains "$FAKE_TOKEN" "$TMP/hidden.txt"
SCAN_ENV=(CODESCAN_TOKEN="$FAKE_TOKEN" CODESCAN_ORG="$FAKE_ORG" CODESCAN_PROJECT="$FAKE_PROJECT")
check "recording scan passes on the clean recordings" env "${SCAN_ENV[@]}" python3 -I "$HERE/scan-recordings.py" "$TMP/rec" "$TMP/hidden.txt"
mkdir -p "$TMP/planted"; cp "$TMP/rec"/*.json "$TMP/planted/"
echo '{"request":{"urlPath":"/x"},"response":{"body":"see sso.fake-sso-corp.example.net"}}' > "$TMP/planted/98-planted.json"
check "recording scan catches a login host from the hidden list" bash -c "! env CODESCAN_TOKEN='$FAKE_TOKEN' python3 -I '$HERE/scan-recordings.py' '$TMP/planted' '$TMP/hidden.txt'"
stop_fake

# 2b. Bearer refused with 401, basic works: the script falls back and says so.
start_fake basic-only
run_probe "http://127.0.0.1:$PORT"
[ "$PROBE_RC" -eq 0 ] && ok "basic-only server: exit 0" || bad "basic-only server: exit 0 (got $PROBE_RC)"
check "probe output says basic auth" grep -qi 'auth.*basic' "$TMP/probe.out"
check "token is not in the probe output (basic)" not_contains "$FAKE_TOKEN" "$TMP/probe.out"
BASIC_B64="$(printf '%s:' "$FAKE_TOKEN" | base64)"
check "base64 of token is not in the probe output" not_contains "$BASIC_B64" "$TMP/probe.out"
stop_fake

# 2c. Both styles refused: exit non-zero, never report success.
start_fake reject-all
run_probe "http://127.0.0.1:$PORT"
[ "$PROBE_RC" -eq 3 ] && ok "rejected token: exit 3" || bad "rejected token: exit 3 (got $PROBE_RC)"
check "token is not in the probe output (rejected)" not_contains "$FAKE_TOKEN" "$TMP/probe.out"
stop_fake

# 2c2. A call that should be recorded gets no answer mid-run: exit non-zero, keep the old recordings.
mkdir -p "$TMP/rec-drop"; echo '{"keep":"old"}' > "$TMP/rec-drop/40-tree.json"
start_fake drop-tree
run_probe "http://127.0.0.1:$PORT" --record "$TMP/rec-drop"
[ "$PROBE_RC" -eq 4 ] && ok "a call with no answer: exit 4" || bad "a call with no answer: exit 4 (got $PROBE_RC)"
check "a call with no answer: the output names it" grep -qE 'no answer.*tree' "$TMP/probe.out"
check "a call with no answer: old recordings are not replaced" grep -q '"keep":"old"' "$TMP/rec-drop/40-tree.json"
check "token is not in the probe output (no answer)" not_contains "$FAKE_TOKEN" "$TMP/probe.out"
stop_fake

# 2d. Unreachable server: exit non-zero, fast.
START=$(date +%s)
run_probe "http://127.0.0.1:9"
END=$(date +%s)
[ "$PROBE_RC" -eq 2 ] && ok "unreachable server: exit 2" || bad "unreachable server: exit 2 (got $PROBE_RC)"
[ $((END - START)) -le 15 ] && ok "unreachable server: finished in $((END - START))s (bounded)" || bad "unreachable server took $((END - START))s"
check "unreachable: message says so" grep -qi 'unreachable' "$TMP/probe.out"
check "token is not in the probe output (unreachable)" not_contains "$FAKE_TOKEN" "$TMP/probe.out"

CODESCAN_TEST_URL="" CODESCAN_TEST_ORG="" CODESCAN_TEST_TOKEN="" bash "$PROBE" > "$TMP/probe.out" 2>&1
MISSING_RC=$?
[ "$MISSING_RC" -eq 64 ] && ok "missing settings: exit 64" || bad "missing settings: exit 64 (got $MISSING_RC)"

echo "== 3. committed recordings hold none of the real secrets"

# the generic patterns must not flag ordinary words that follow "Basic" or "Bearer" (rule texts say "Basic authenticated")
mkdir -p "$TMP/prose" "$TMP/creds"
echo '{"request":{"urlPath":"/x"},"response":{"body":"Basic authenticated users. Basic Authentication is weak. Bearer authentication too."}}' > "$TMP/prose/01.json"
echo '{"request":{"urlPath":"/x"},"response":{"body":"Authorization: Basic dXNlcjpwYXNzMTIzNDU2Nzg="}}' > "$TMP/creds/01.json"
echo '{"request":{"urlPath":"/x"},"response":{"body":"Authorization: Bearer eyJhbGciOi.J9abc123_x-yz"}}' > "$TMP/creds/02.json"
check "scan lets ordinary words after Basic or Bearer pass" env -u CODESCAN_TOKEN python3 -I "$HERE/scan-recordings.py" "$TMP/prose"
check "scan still flags a Basic credential" bash -c "python3 -I '$HERE/scan-recordings.py' '$TMP/creds' | grep -q 'Basic'"
check "scan still flags a Bearer credential" bash -c "python3 -I '$HERE/scan-recordings.py' '$TMP/creds' | grep -q 'Bearer'"

REAL_ENV="${CODESCAN_ENV_FILE:-/tmp/cs-research/staging.env}"
if [ -z "${CODESCAN_TOKEN:-}" ] && [ -r "$REAL_ENV" ]; then
  # shellcheck disable=SC1090
  set -a; . "$REAL_ENV"; set +a
fi
REC_COUNT=$(find "$RECORDINGS_DIR" -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
[ "$REC_COUNT" -gt 0 ] && ok "recordings exist ($REC_COUNT files)" || bad "no recordings found in $RECORDINGS_DIR"

SCAN="$HERE/scan-recordings.py"
HIDDEN_LIST="${CODESCAN_HIDDEN_LIST:-$HERE/.hidden-values.txt}"
python3 -I "$SCAN" "$RECORDINGS_DIR" "$HIDDEN_LIST" > "$TMP/scan.out" 2>&1
SCAN_RC=$?
report_scan "$SCAN_RC" "$TMP/scan.out"

# short hidden values (logins under 4 characters) are looked for only as whole values
mkdir -p "$TMP/shortscan/bad" "$TMP/shortscan/badpb" "$TMP/shortscan/good"
printf 'bo\n' > "$TMP/short-hidden.txt"
echo '{"request":{"urlPath":"/x"},"response":{"body":"{\"assignee\":\"bo\"}"}}' > "$TMP/shortscan/bad/01.json"
echo '{"request":{"urlPath":"/x"},"response":{"body":"a robot said bo-bo"}}' > "$TMP/shortscan/good/01.json"
python3 -I - "$TMP/shortscan/badpb/01.json" <<'PYEOF2'
import base64, json, sys
body = b"\x0a\x02bo" + b"\x12\x05robot"
json.dump({"request": {"urlPath": "/x"}, "response": {"base64Body": base64.b64encode(body).decode()}}, open(sys.argv[1], "w"))
PYEOF2
check "scan flags a short hidden value that is a whole JSON value" bash -c "! env -u CODESCAN_TOKEN python3 -I '$SCAN' '$TMP/shortscan/bad' '$TMP/short-hidden.txt' >/dev/null"
check "scan flags a short hidden value that is a whole protobuf field" bash -c "! env -u CODESCAN_TOKEN python3 -I '$SCAN' '$TMP/shortscan/badpb' '$TMP/short-hidden.txt' >/dev/null"
check "scan lets a short hidden value inside other words pass" bash -c "env -u CODESCAN_TOKEN python3 -I '$SCAN' '$TMP/shortscan/good' '$TMP/short-hidden.txt' >/dev/null"

# Made-up values only. Each planted leak sits alone in its own folder, so each one must be caught on its own.
LT="$TMP/leaktest"
mkdir -p "$LT"
printf 'Acme Widgets Ltd\nzq9\nsecret.host.example\nJos\xc3\xa9 P\xc3\xa9rez\n' > "$LT/hidden.txt"
LT_TOKEN="abcd1234tokenvalue"
plant() { mkdir -p "$LT/$1/$(dirname "$2")"; printf '%s' "$3" > "$LT/$1/$2"; }
plant urlenc   mappings/a.json '{"request":{"urlPath":"/api/x"},"response":{"status":200,"body":"see Acme%20Widgets%20Ltd"}}'
plant plus     mappings/b.json '{"request":{"url":"/api/x?name=Acme+Widgets+Ltd"},"response":{"status":200}}'
plant nonascii mappings/c.json '{"request":{"urlPath":"/a"},"response":{"status":200,"jsonBody":{"name":"José Pérez"}}}'
plant b64tok   mappings/d.json "{\"request\":{\"urlPath\":\"/a\"},\"response\":{\"status\":200,\"body\":\"$(printf '%s:' "$LT_TOKEN" | base64)\"}}"
plant b64mid   mappings/d2.json "{\"request\":{\"urlPath\":\"/a\"},\"response\":{\"status\":200,\"body\":\"$(printf 'xy said Acme Widgets Ltd' | base64)\"}}"
plant short    mappings/e.json '{"request":{"urlPath":"/a"},"response":{"status":200,"jsonBody":{"login":"zq9"}}}'
plant shortq   mappings/e2.json '{"request":{"url":"/a?login=zq9&x=1"},"response":{"status":200}}'
plant upper    mappings/f.json '{"request":{"urlPath":"/a"},"response":{"status":200,"body":"SECRET.HOST.EXAMPLE"}}'
plant bodyfile mappings/h.json '{"request":{"urlPath":"/a"},"response":{"status":200,"bodyFileName":"x.txt"}}'
plant bodyfile __files/x.txt 'secret.host.example'
plant escjson  mappings/g.json '{"request":{"urlPath":"/a"},"response":{"status":200,"body":"{\"name\":\"Jos\\u00e9 P\\u00e9rez\"}"}}'
plant clean    mappings/z.json '{"request":{"urlPath":"/a"},"response":{"status":200,"body":"Acme gadgets, secret host, Jose, zq90"}}'
for case in urlenc plus nonascii b64tok b64mid short shortq upper bodyfile escjson; do
  check "scan catches a planted leak: $case" bash -c "! env CODESCAN_TOKEN='$LT_TOKEN' python3 -I '$SCAN' '$LT/$case' '$LT/hidden.txt' >/dev/null"
done
check "scan names the file under __files/" bash -c "env CODESCAN_TOKEN='$LT_TOKEN' python3 -I '$SCAN' '$LT/bodyfile' '$LT/hidden.txt' | grep -q 'x.txt'"
check "scan passes a file with only look-alike words" env CODESCAN_TOKEN="$LT_TOKEN" python3 -I "$SCAN" "$LT/clean" "$LT/hidden.txt"

printf 'SKIP: CODESCAN_TOKEN not set, token not checked\n' > "$TMP/fake-scan-skip.out"
: > "$TMP/fake-scan-full.out"
( PASS=0; FAIL=0; report_scan 0 "$TMP/fake-scan-skip.out"; echo "counts pass=$PASS fail=$FAIL" ) > "$TMP/report-skip.txt" 2>&1
( PASS=0; FAIL=0; report_scan 0 "$TMP/fake-scan-full.out"; echo "counts pass=$PASS fail=$FAIL" ) > "$TMP/report-full.txt" 2>&1
( PASS=0; FAIL=0; report_scan 1 "$TMP/fake-scan-skip.out"; echo "counts pass=$PASS fail=$FAIL" ) > "$TMP/report-leak.txt" 2>&1
check "real values missing: says the real-value comparison was NOT run" grep -q 'real-value comparison NOT run' "$TMP/report-skip.txt"
check "real values missing: prints no PASS for that section" bash -c "! grep -q 'PASS' '$TMP/report-skip.txt'"
check "real values missing: not counted as a failure" grep -q 'counts pass=0 fail=0' "$TMP/report-skip.txt"
check "real values loaded and nothing found: PASS" grep -q 'counts pass=1 fail=0' "$TMP/report-full.txt"
check "scan found something: FAIL" grep -q 'counts pass=0 fail=1' "$TMP/report-leak.txt"

mkdir -p "$TMP/walk/docs/qa" "$TMP/walk/repo/.worktrees/t3/codescan-tools" "$TMP/walk/repo/codescan-tools" "$TMP/nowalk/a/b"
touch "$TMP/walk/docs/qa/codescan-endpoints.md"
check "docs page is found from a worktree" test "$(find_docs_page "$TMP/walk/repo/.worktrees/t3/codescan-tools")" = "$TMP/walk/docs/qa/codescan-endpoints.md"
check "docs page is found from the main checkout" test "$(find_docs_page "$TMP/walk/repo/codescan-tools")" = "$TMP/walk/docs/qa/codescan-endpoints.md"
check "no docs page anywhere above: nothing is returned" test -z "$(find_docs_page "$TMP/nowalk/a/b")"

# the docs page is scanned as one text file
printf 'assignee-person-5\n' > "$TMP/doc-hidden.txt"
echo 'The table shows a row for assignee-person-5.' > "$TMP/doc-bad.md"
echo 'The table shows a row for test-user.' > "$TMP/doc-good.md"
check "scan reads a single docs file and flags a hidden value in it" bash -c "! env -u CODESCAN_TOKEN python3 -I '$SCAN' '$TMP/doc-bad.md' '$TMP/doc-hidden.txt' >/dev/null"
check "scan reads a single docs file and passes a clean one" bash -c "env -u CODESCAN_TOKEN python3 -I '$SCAN' '$TMP/doc-good.md' '$TMP/doc-hidden.txt' >/dev/null"
DOCS_FILE="${CODESCAN_DOCS_FILE:-$(find_docs_page "$HERE")}"
if [ -n "$DOCS_FILE" ] && [ -f "$DOCS_FILE" ]; then
  python3 -I "$SCAN" "$DOCS_FILE" "$HIDDEN_LIST" > "$TMP/docs-scan.out" 2>&1
  report_scan $? "$TMP/docs-scan.out" "the docs page"
else
  echo "    SKIP: docs page not found (no docs/qa/codescan-endpoints.md in any folder above $HERE)"
fi

echo "== 3b. committed recordings: the addresses core sends, and the shape of every stream"
python3 -I "$HERE/check-recordings.py" "$RECORDINGS_DIR" all > "$TMP/check-rec.out" 2>&1
CHECK_REC_RC=$?
[ "$CHECK_REC_RC" -eq 0 ] && ok "every address core 10.12.1 sends matches exactly one mapping; every stream and message parses" \
  || { bad "recordings do not fit core's requests (exit $CHECK_REC_RC)"; grep -A1 '^FAIL' "$TMP/check-rec.out" | head -30; }
STREAMS_SEEN="$(grep -c 'stream of [0-9]* message' "$TMP/check-rec.out")"
[ "$STREAMS_SEEN" -ge 1 ] && ok "stream bodies were checked ($STREAMS_SEEN)" || bad "no stream body was checked, so the stream check proves nothing"
# api/sources/raw: the file keys come from the answers core reads, not from the sources recording's own key
count_ok() { grep -cE "^ok    $1" "$TMP/check-rec.out"; }
SRC_TAINT="$(count_ok 'source of a file of a taint vulnerability')"
[ "$SRC_TAINT" -ge 2 ] && ok "sources: every file of the taint vulnerabilities (issues/search, types=VULNERABILITY) is asked for ($SRC_TAINT)" \
  || bad "sources: the files of the taint vulnerabilities are not all asked for and answered ($SRC_TAINT ok)"
SRC_ISSUE="$(count_ok 'source of the file of an issue opened from the browser')"
[ "$SRC_ISSUE" -ge 1 ] && ok "sources: the file of the issue fetched by key (with branch) is asked for ($SRC_ISSUE)" \
  || bad "sources: the file of the issue fetched by key is not asked for and answered"
SRC_HOTSPOT="$(count_ok 'source of the file of a hotspot opened from the browser')"
[ "$SRC_HOTSPOT" -ge 1 ] && ok "sources: the file of the hotspot (hotspots/show) is asked for ($SRC_HOTSPOT)" \
  || bad "sources: the file of the hotspot is not asked for and answered"
check "sources: no source call is left without an answer" bash -c "! grep -q '^FAIL source' '$TMP/check-rec.out'"

echo "== 3c. the address check follows every paged call the way core does (ServerApiHelper.getPaginated)"
# Test helper: rewrite one recording's paging. Usage: repage.py <file> <where> <total> [p=<n>|p=any] [no-items=<field>]
#   where = paging:<field>  -> set Paging.total (field 3) inside top-level message field <field>
#           top:<field>     -> set the top-level varint field <field>
cat > "$TMP/repage.py" <<'PYEOF'
import base64, json, sys
sys.path.insert(0, sys.argv[1])
import protowire as pw
f, where, total = sys.argv[2], sys.argv[3], int(sys.argv[4])
opts = dict(a.split("=", 1) for a in sys.argv[5:])
kind, num = where.split(":")
num = int(num)
m = json.load(open(f))
body = base64.b64decode(m["response"]["base64Body"])
def num_of(t):
    return pw.read_varint(t, 0)[0] >> 3
def set_varint(fields, n, value):
    out, done = [], False
    for t, w, p, raw in fields:
        if num_of(t) == n and w == 0:
            out.append(t + pw.write_varint(value)); done = True
        else:
            out.append(raw)
    if not done:
        out.append(pw.write_varint(n << 3) + pw.write_varint(value))
    return b"".join(out)
fields = pw.split_fields(body)
if "no-items" in opts:
    fields = [x for x in fields if num_of(x[0]) != int(opts["no-items"])]
if kind == "top":
    body = set_varint(fields, num, total)
else:
    out = []
    for t, w, p, raw in fields:
        if num_of(t) == num and w == 2:
            inner = set_varint(pw.split_fields(p), 3, total)
            out.append(t + pw.write_varint(len(inner)) + inner)
        else:
            out.append(raw)
    body = b"".join(out)
m["response"]["base64Body"] = base64.b64encode(body).decode()
p = opts.get("p")
if p == "any":
    m["request"]["queryParameters"]["p"] = {"matches": "[0-9]+"}
elif p:
    m["request"]["queryParameters"]["p"] = {"equalTo": p}
json.dump(m, open(opts.get("out", f), "w"), indent=2)
PYEOF
CHECK_REC="$HERE/check-recordings.py"
fresh_recs() { rm -rf "$TMP/rp"; cp -R "$RECORDINGS_DIR" "$TMP/rp"; }
repage() { python3 -I "$TMP/repage.py" "$HERE" "$TMP/rp/$1" "${@:2}"; }
# expect_page <name> <exit wanted: 0|1> <grep pattern that must be in the output>
expect_page() {
  python3 -I "$CHECK_REC" "$TMP/rp" core-urls > "$TMP/rp.out" 2>&1
  local rc=$?
  if [ "$rc" -eq "$2" ] && grep -qE -- "$3" "$TMP/rp.out"; then ok "$1"; else bad "$1 (exit $rc)"; grep -E '^FAIL' "$TMP/rp.out" | head -5; fi
}

fresh_recs; python3 -I - "$TMP/rp" <<'PYEOF'
import glob, json, sys
for f in glob.glob(sys.argv[1] + "/*sources-raw-fallback.json"):
    m = json.load(open(f)); m["metadata"].pop("fallback", None); json.dump(m, open(f, "w"))
PYEOF
expect_page "a catch-all sources mapping not marked as fallback: two recordings match, fails" 1 "^FAIL source.*: 2 recordings match"

fresh_recs; repage 40-tree.json paging:1 600
expect_page "file tree with 600 files: page 2 is asked for and missing" 1 "^FAIL all file keys .*page 2: no recording matches"
repage 40-tree.json paging:1 600 p=2 out="$TMP/rp/40-tree-p2.json"
expect_page "file tree with 600 files and a page 2 recording: passes, stops after page 2" 0 "all file keys .*, page 2"
fresh_recs; repage 40-tree.json paging:1 600 no-items=4
expect_page "file tree page 1 with no files: core stops (empty page), so no page 2" 0 "all file keys .*, page 1"
grep -q "all file keys .*, page 2" "$TMP/rp.out" && bad "empty page still asked for page 2" || ok "empty page: page 2 not asked for"

fresh_recs; repage 45-issues-vulns.json paging:4 600
expect_page "vulnerabilities with 600 issues: page 2 is asked for and missing" 1 "^FAIL taint vulnerabilities.*page 2: no recording matches"
fresh_recs; repage 45-issues-vulns.json paging:4 100000 p=any
expect_page "vulnerabilities: core stops at 20 pages" 0 "taint vulnerabilities, with taint rules, page 20"
grep -q "taint vulnerabilities.*, page 21" "$TMP/rp.out" && bad "vulnerabilities went past 20 pages" || ok "vulnerabilities: no page 21"
fresh_recs; repage 40-tree.json paging:1 12000 p=any
expect_page "file tree has no 20-page limit (24 pages for 12000 files)" 0 "all file keys .*, page 24"

fresh_recs; repage 09-components-search.json paging:1 600
expect_page "all projects with 600 projects: page 2 is asked for" 1 "^FAIL all projects .*page 2: no recording matches"
fresh_recs; repage 52-hotspots-search.json paging:1 600
expect_page "hotspots with 600 hotspots: page 2 is asked for" 1 "^FAIL hotspots of the project .*page 2: no recording matches"
fresh_recs; repage 07-orgs-member.json paging:2 600
expect_page "organisations of the user, 600 of them: page 2 is asked for" 1 "^FAIL organisations of the user .*page 2: no recording matches"
fresh_recs; repage 23-rules-by-profile.json paging:8 600
expect_page "rules by profile: the total in the paging block (field 8) is the one core reads" 1 "^FAIL rules of the .* profile .*page 2: no recording matches"

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
