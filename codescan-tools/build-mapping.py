#!/usr/bin/env python3
"""Turn raw probe results into WireMock mapping files (not yet scrubbed).

Usage: build-mapping.py <raw-dir> <mapping-dir>

<raw-dir> holds, for each recorded probe, NAME.meta.json
({"path": "api/x?a=b", "status": 200, "contentType": "...", "bodyFile": "NAME.body"}) and the body file.
Text bodies (JSON, plain text) are stored as "body"; everything else as "base64Body".
Optional meta fields: "note" (goes to metadata.note), "ignoreParams" (query parameters left out of the match),
"anyValueParams" (query parameters matched with any value),
"requestHeaders" (header name -> exact value the request must carry), "priority" (WireMock priority, lower wins),
"fallback": true (goes to metadata.fallback: a catch-all stand-in that a better-priority recording overrides;
check-recordings.py accepts the overlap only for these).
"""
import base64
import json
import os
import sys
import urllib.parse


def is_text(content_type):
    ct = (content_type or "").lower()
    return ct.startswith("text/") or "json" in ct or "xml" in ct


def build(meta, body):
    parsed = urllib.parse.urlsplit("/" + meta["path"].lstrip("/"))
    request = {"method": "GET", "urlPath": parsed.path}
    params = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    ignore = set(meta.get("ignoreParams", []))
    any_value = set(meta.get("anyValueParams", []))
    matchers = {}
    for k, v in params:
        if k in ignore:
            continue
        matchers[k] = {"matches": ".*"} if k in any_value else {"equalTo": v}
    if matchers:
        request["queryParameters"] = matchers
    if meta.get("requestHeaders"):
        request["headers"] = {k: {"equalTo": v} for k, v in meta["requestHeaders"].items()}
    response = {"status": meta["status"]}
    if meta.get("contentType"):
        response["headers"] = {"Content-Type": meta["contentType"]}
    if is_text(meta.get("contentType")):
        try:
            response["body"] = body.decode("utf-8")
        except UnicodeDecodeError:
            response["base64Body"] = base64.b64encode(body).decode()
    elif body:
        response["base64Body"] = base64.b64encode(body).decode()
    mapping = {"request": request, "response": response}
    if meta.get("priority"):
        mapping = {"priority": int(meta["priority"]), **mapping}
    if meta.get("note"):
        mapping["metadata"] = {"note": meta["note"]}
    if meta.get("fallback") is True:
        mapping.setdefault("metadata", {})["fallback"] = True
    return mapping


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 64
    raw_dir, out_dir = argv[1], argv[2]
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    for name in sorted(os.listdir(raw_dir)):
        if not name.endswith(".meta.json"):
            continue
        with open(os.path.join(raw_dir, name), encoding="utf-8") as f:
            meta = json.load(f)
        with open(os.path.join(raw_dir, meta["bodyFile"]), "rb") as f:
            body = f.read()
        mapping = build(meta, body)
        with open(os.path.join(out_dir, name[: -len(".meta.json")] + ".json"), "w", encoding="utf-8") as f:
            json.dump(mapping, f, indent=2)
        count += 1
    print("build-mapping: %d mapping(s)" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
