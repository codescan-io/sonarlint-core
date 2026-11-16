#!/usr/bin/env python3
"""Scrub WireMock mapping files so they can be committed.

Usage:   scrub-recording.py <input-dir> <output-dir>

Reads the real values from the environment (never from the command line):
  CODESCAN_TEST_ORG      organisation key       -> test-org
  CODESCAN_TEST_PROJECT  project key            -> test-project
  CODESCAN_TEST_TOKEN    access token           -> removed (REDACTED)
  CODESCAN_SCRUB_USERS   list of user logins / display names -> user-1, user-2, ...
  CODESCAN_SCRUB_EXTRA   list of "real=replacement" pairs (for example an organisation display name)
  (A list is one entry per line if it has a newline in it, so a name may hold a comma; otherwise comma separated.)
  CODESCAN_SCRUB_EXACT   list of short user logins (under 4 characters). They are replaced (-> user-short-1, ...)
                         only where they are the whole value (a whole JSON string, a quoted value in text, a whole
                         protobuf field), never inside other words

Values are replaced in any letter case, and also where they are written URL encoded (%20 or +), with JSON \\u
escapes, or in Unicode decomposed form. Also always replaced: every e-mail address (-> user@example.test), request
and response headers that carry credentials or cookies.

Protobuf bodies (response.base64Body): strings inside are replaced and the length prefix of every
enclosing message is rewritten, so the body stays a valid protobuf message. Nothing is changed in
a field that holds nothing to scrub. The shape of the body comes from the address:
  * api/issues/pull, api/issues/pull_taint, api/hotspots/pull, batch/issues: a stream of length-prefixed
    messages. Every message is scrubbed on its own and every length prefix is rewritten.
  * any other .protobuf address (status below 400): one message.
  * anything else: bytes are replaced as they are.
Which field is text and which is a nested message comes from core 10.12.1's .proto files (proto-schema.json, see
build-proto-schema.py). A field the schema does not know is guessed: bytes that do not parse, or that hold
nothing to replace, are easy. If the bytes are readable text and also parse as a message, and replacing them as
text gives a different result than replacing them as a message, the script cannot tell which is right: it says
so (naming the schema), writes nothing and exits 1.
If a stream or message does not parse, the script says so, writes nothing and exits 1. It never reports
success for output that does not parse.

Request headers are dropped, except one Authorization header whose value is exactly "Bearer REDACTED"
(the made-up token used for the bad-token recording).

After writing, the script re-reads every output file, checks the structure again (every nested message the
schema declares must parse), and looks for every real value in every form scan-recordings.py looks for
(leakfind.py). If any is still present it exits 1 and deletes the output.
"""
import base64
import json
import os
import re
import sys
import unicodedata
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import leakfind  # noqa: E402
import protoschema  # noqa: E402
import protowire  # noqa: E402
from protowire import split_fields, write_varint  # noqa: E402

STREAM_PATHS = {"/api/issues/pull", "/api/issues/pull_taint", "/api/hotspots/pull", "/batch/issues"}
MADE_UP_AUTH = {"equalTo": "Bearer REDACTED"}

EMAIL = re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
SECRET_HEADERS = {"authorization", "proxy-authorization", "cookie", "set-cookie", "x-sonar-passcode"}
KEEP_RESPONSE_HEADERS = {"content-type"}


def env_list(name):
    """A list from the environment: one entry per line if there is a newline, else a comma list."""
    value = os.environ.get(name, "")
    return [x for x in (value.split("\n") if "\n" in value else value.split(",")) if x]


def replacement_table():
    """Ordered list of (pattern, replacement text), longest real values first; plus the list of real values."""
    pairs, reals = [], []

    def add(real, repl, any_case=True):
        if not real:
            return
        reals.append(real)
        forms = {real, unicodedata.normalize("NFD", real), urllib.parse.quote(real, safe=""),
                 urllib.parse.quote_plus(real), json.dumps(real)[1:-1]}
        for form in forms:
            pairs.append((form, repl, any_case))

    token = os.environ.get("CODESCAN_TEST_TOKEN", "")
    if token:
        add(token, "REDACTED", any_case=False)
        for form in (base64.b64encode(token.encode()).decode(), base64.b64encode((token + ":").encode()).decode()):
            pairs.append((form, "REDACTED", False))
    add(os.environ.get("CODESCAN_TEST_ORG", ""), "test-org")
    add(os.environ.get("CODESCAN_TEST_PROJECT", ""), "test-project")
    for i, user in enumerate(env_list("CODESCAN_SCRUB_USERS"), 1):
        add(user, "user-%d" % i)
    for pair in [p for p in env_list("CODESCAN_SCRUB_EXTRA") if "=" in p]:
        real, repl = pair.split("=", 1)
        add(real, repl)
    pairs.sort(key=lambda p: -len(p[0]))
    flags = lambda any_case: re.IGNORECASE if any_case else 0  # noqa: E731
    compiled = [(re.compile(re.escape(real), flags(c)), re.compile(re.escape(real.encode()), flags(c)), repl)
                for real, repl, c in pairs]
    return compiled, reals


def exact_table():
    """Short logins that are replaced only as a whole value: {casefolded real text: replacement text}."""
    names = env_list("CODESCAN_SCRUB_EXACT")
    return {leakfind.norm(name): "user-short-%d" % i for i, name in enumerate(names, 1)}


PAIRS, REALS = replacement_table()
EXACT = exact_table()
EXACT_NAMES = env_list("CODESCAN_SCRUB_EXACT")
EXACT_QUOTED = [(re.compile(r'(\\*")(%s)(\\*")' % re.escape(n), re.IGNORECASE), EXACT[leakfind.norm(n)])
                for n in EXACT_NAMES]
EXACT_QUERY = [(re.compile(r'([?&][^=&#"]*=)(%s)(?=[&#"]|$)' % re.escape(n), re.IGNORECASE), EXACT[leakfind.norm(n)])
               for n in EXACT_NAMES]


class ScrubError(Exception):
    pass


def exact(text):
    """The replacement if the whole text is a short login (any letter case), else None."""
    return EXACT.get(leakfind.norm(text)) if EXACT else None


def scrub_exact_text(text):
    """Replace a short login where it is a whole quoted value (plain or inside an escaped JSON string) or a whole
    query parameter value."""
    for rx, repl in EXACT_QUOTED:
        text = rx.sub(lambda m, r=repl: m.group(1) + r + m.group(3), text)
    for rx, repl in EXACT_QUERY:
        text = rx.sub(lambda m, r=repl: m.group(1) + r, text)
    return text


def scrub_bytes(data):
    try:
        return scrub_str(data.decode("utf-8")).encode("utf-8")
    except UnicodeDecodeError:
        pass
    for _, rx, repl in PAIRS:
        data = rx.sub(lambda m, r=repl.encode(): r, data)
    return EMAIL.sub(b"user@example.test", data)


def scrub_str(text):
    for rx, _, repl in PAIRS:
        text = rx.sub(lambda m, r=repl: r, text)
    return EMAIL.sub(b"user@example.test", text.encode("utf-8")).decode("utf-8")


def scrub_text_body(text):
    return scrub_exact_text(scrub_str(text))


def scrub_leaf(buf):
    """A string or bytes field: the whole value if it is a short login, else every real value in it."""
    if EXACT:
        try:
            whole = exact(buf.decode("utf-8"))
        except UnicodeDecodeError:
            whole = None
        if whole:
            return whole.encode()
    return scrub_bytes(buf)


def scrub_message(buf, type_name, where):
    """Replace real values inside one protobuf message and rewrite the length of every field that changed.

    type_name is the message type from core's .proto files, or None where the schema does not say. Fields the
    schema calls string/bytes are text, fields it calls a message are opened.
    """
    try:
        fields = split_fields(buf)
    except ValueError as e:
        raise ScrubError("%s does not parse as %s: %s" % (where, type_name or "a message", e))
    out = bytearray()
    for tag_bytes, wire, payload, raw in fields:
        if wire != 2:
            out += raw
            continue
        number = protoschema.field_number(tag_bytes)
        spec = protoschema.field_spec(type_name, number)
        here = "%s.%d" % (where, number)
        if spec is None:
            new = scrub_unknown(payload, here)
        elif spec[0] == "message":
            new = scrub_message(payload, spec[1], "%s (%s)" % (here, spec[1]))
        elif spec[0] in ("string", "bytes"):
            new = scrub_leaf(payload)
        else:
            # packed numbers, or bytes that do not fit the schema: guessed like a field the schema does not know
            new = scrub_unknown(payload, here)
        out += raw if new == payload else tag_bytes + write_varint(len(new)) + new
    return bytes(out)


def scrub_unknown(buf, where):
    """A length-delimited field the schema does not describe: text or a nested message, guessed from the bytes."""
    as_text = scrub_leaf(buf)
    try:
        split_fields(buf)
    except ValueError:
        return as_text
    try:
        as_message = scrub_message(buf, None, where)
    except ScrubError:
        return as_text
    if not leakfind.text_like(buf) or as_message == as_text:
        return as_message
    if as_message == buf:
        # read as a message, no field holds the value, so the value crosses field boundaries: it is text
        return as_text
    raise ScrubError("%s: the schema (proto-schema.json) does not describe this field, and its bytes read both as "
                     "text and as a nested message that would be scrubbed differently; add the address to "
                     "build-proto-schema.py" % where)


def scrub_stream(buf, path):
    """Scrub a stream of length-prefixed messages: every message on its own, every prefix rewritten."""
    try:
        messages = protowire.split_delimited(buf)
    except ValueError as e:
        raise ScrubError("body is not a stream of length-prefixed messages: %s" % e)
    first, rest = protoschema.stream_types(path)
    return protowire.join_delimited([scrub_message(m, first if i == 0 else rest, "message %d" % (i + 1))
                                     for i, m in enumerate(messages)])


def body_kind(mapping):
    """stream | message | other, from the address and the status."""
    path = mapping.get("request", {}).get("urlPath", "")
    status = mapping.get("response", {}).get("status", 200)
    if path in STREAM_PATHS and status < 400:
        return "stream"
    if path.endswith(".protobuf") and status < 400:
        return "message"
    return "other"


def scrub_base64_body(raw, kind, path=""):
    if kind == "stream":
        return scrub_stream(raw, path)
    if kind == "message":
        try:
            split_fields(raw)
        except ValueError as e:
            raise ScrubError("body is not a protobuf message: %s" % e)
        return scrub_message(raw, protoschema.root_type(path), "body")
    return scrub_bytes(raw)


def scrub_json_value(value):
    if isinstance(value, str):
        return exact(value) or scrub_exact_text(scrub_str(value))
    if isinstance(value, list):
        return [scrub_json_value(v) for v in value]
    if isinstance(value, dict):
        return {scrub_str(k): scrub_json_value(v) for k, v in value.items()}
    return value


def scrub_headers(headers, keep_only=None):
    result = {}
    for name, value in headers.items():
        lower = name.lower()
        if keep_only is None and lower == "authorization" and value == MADE_UP_AUTH:
            result[name] = value
            continue
        if lower in SECRET_HEADERS or "token" in lower:
            continue
        if keep_only is not None and lower not in keep_only:
            continue
        result[name] = scrub_json_value(value)
    return result


def scrub_mapping(mapping):
    request = mapping.get("request", {})
    if "headers" in request:
        request["headers"] = scrub_headers(request["headers"])
    mapping["request"] = scrub_json_value(request)

    response = mapping.get("response", {})
    if "headers" in response:
        response["headers"] = scrub_headers(response["headers"], KEEP_RESPONSE_HEADERS)
    for key in ("jsonBody", "bodyFileName", "statusMessage"):
        if key in response:
            response[key] = scrub_json_value(response[key])
    if "body" in response:
        response["body"] = scrub_text_body(response["body"]) if isinstance(response["body"], str) \
            else scrub_json_value(response["body"])
    if "base64Body" in response:
        raw = base64.b64decode(response["base64Body"])
        path = mapping.get("request", {}).get("urlPath", "")
        response["base64Body"] = base64.b64encode(scrub_base64_body(raw, body_kind(mapping), path)).decode()
    mapping["response"] = response
    for key in ("name", "id", "uuid"):
        if key in mapping and isinstance(mapping[key], str):
            mapping[key] = scrub_str(mapping[key])
    return mapping


FINDER = leakfind.Finder(REALS + EXACT_NAMES)


def leftovers(path):
    """Return a list of problems: real values still present in any form, or a body that does not parse."""
    with open(path, "rb") as f:
        raw = f.read()
    views = leakfind.Views().add_file(raw, is_json=True)
    problems = list(views.errors)
    try:
        mapping = json.loads(raw)
    except ValueError:
        return problems or ["not valid JSON"]
    b64 = mapping.get("response", {}).get("base64Body")
    if b64:
        body = base64.b64decode(b64)
        kind = body_kind(mapping)
        try:
            if kind == "stream":
                protowire.split_delimited(body)
            elif kind == "message":
                split_fields(body)
        except ValueError as e:
            problems.append("base64Body does not parse as a %s: %s" % (kind, e))
        if kind != "other":
            problems += ["base64Body: " + p for p in
                         protoschema.body_problems(mapping.get("request", {}).get("urlPath", ""), body)]
    problems += ["still " + p for p in FINDER.find(views)]
    if any(EMAIL.search(t.encode("utf-8").replace(b"user@example.test", b"")) for t in views.plain):
        problems.append("still holds an e-mail address")
    return problems


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 64
    in_dir, out_dir = argv[1], argv[2]
    if not PAIRS:
        print("scrub-recording: no CODESCAN_TEST_* values set, refusing to run blind", file=sys.stderr)
        return 64
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for name in sorted(os.listdir(in_dir)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(in_dir, name), "r", encoding="utf-8") as f:
            mapping = json.load(f)
        target = os.path.join(out_dir, scrub_str(name))
        try:
            scrubbed = scrub_mapping(mapping)
        except ScrubError as e:
            print("scrub-recording: %s: %s" % (name, e), file=sys.stderr)
            for done in written:
                os.remove(done)
            return 1
        with open(target, "w", encoding="utf-8") as f:
            json.dump(scrubbed, f, indent=2, sort_keys=False)
            f.write("\n")
        written.append(target)
    failed = False
    for target in written:
        for problem in leftovers(target):
            print("scrub-recording: %s: %s" % (os.path.basename(target), problem), file=sys.stderr)
            failed = True
    if failed:
        for target in written:
            os.remove(target)
        return 1
    print("scrub-recording: wrote %d file(s) to %s" % (len(written), out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
