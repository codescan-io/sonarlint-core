#!/usr/bin/env python3
"""Check that the recordings fit what sonarlint-core 10.12.1 really asks for, and that the protobuf bodies parse.

Usage: check-recordings.py <recordings dir> core-urls|streams|all [--quiet]

core-urls  Builds every address core 10.12.1 sends for a SonarCloud-type connection (the way the CodeScan
           plugin connects) and checks that each one matches exactly one recording, using WireMock's own
           matching rules (urlPath, queryParameters with equalTo / matches, headers with equalTo / matches).
           The address list follows backend/server-api and its callers in tag 10.12.1.79852:
             ServerApiHelper.getPaginated  adds ps=500&p=N; stops when a page has no items, or when the total
                                           is above 0 and page*500 >= total; stops at 20 pages only for the
                                           calls that ask for that (all projects, taint vulnerabilities)
             RulesApi.getSearchByQualityProfileUrl / getSearchByRepoUrl, IssueApi, HotspotApi, NewCodeApi ...
           Languages: plugin-languages.txt. Taint repositories come in the order of the SonarLanguage enum
           (RulesApi.getAllTaintRules(List.of(SonarLanguage.values()))).
streams    Parses the recorded protobuf bodies strictly. The pull calls and batch/issues are streams of
           length-prefixed messages (parseDelimitedFrom / readMessages); every message must parse and the
           lengths must add up. Every other .protobuf body must parse as one message. Where core's .proto files
           (proto-schema.json) know the address, every field declared as a nested message must parse too.

Exit 0 if everything holds, 1 otherwise.
"""
import base64
import glob
import json
import os
import re
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import protoschema  # noqa: E402
import protowire  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ORG, PROJECT, BRANCH = "test-org", "test-project", "main"
UNKNOWN_ORG, UNKNOWN_PROJECT = "no-such-org-for-tests", "no-such-project-for-tests"
BAD_TOKEN_HEADER = "Bearer REDACTED"
GOOD_TOKEN_HEADER = "Bearer test-token"
STREAM_PATHS = {"/api/issues/pull", "/api/issues/pull_taint", "/api/hotspots/pull", "/batch/issues"}
# SonarLanguage enum order of core 10.12.1 (only the languages that have a taint repository)
TAINT_REPOS_IN_ENUM_ORDER = ["roslyn.sonaranalyzer.security.cs", "javasecurity", "jssecurity", "phpsecurity",
                             "pythonsecurity", "tssecurity"]
PAGE_SIZE = 500
Q = lambda s: urllib.parse.quote(s, safe="")  # noqa: E731


def load_mappings(rec_dir):
    maps = []
    for f in sorted(glob.glob(os.path.join(rec_dir, "*.json"))):
        m = json.load(open(f, encoding="utf-8"))
        m["_file"] = os.path.basename(f)
        maps.append(m)
    return maps


def _value_ok(cond, value):
    if "equalTo" in cond:
        return value == cond["equalTo"]
    if "matches" in cond:
        return re.fullmatch(cond["matches"], value) is not None
    raise ValueError("matcher not understood by this check: %s" % list(cond))


def match(maps, url, headers=None):
    """Mappings that WireMock would consider for this GET, best priority first."""
    headers = headers or {"Authorization": GOOD_TOKEN_HEADER}
    u = urllib.parse.urlsplit("/" + url.lstrip("/"))
    query = {}
    for k, v in urllib.parse.parse_qsl(u.query, keep_blank_values=True):
        query.setdefault(k, v)
    hits = []
    for m in maps:
        r = m["request"]
        if r.get("method", "ANY") not in ("GET", "ANY") or r["urlPath"] != u.path:
            continue
        if any(k not in query or not _value_ok(c, query[k]) for k, c in r.get("queryParameters", {}).items()):
            continue
        if any(k not in headers or not _value_ok(c, headers[k]) for k, c in r.get("headers", {}).items()):
            continue
        hits.append(m)
    return sorted(hits, key=lambda m: m.get("priority", 5))


def body_bytes(m):
    res = m["response"]
    if "base64Body" in res:
        return base64.b64decode(res["base64Body"])
    return (res.get("body") or "").encode("utf-8")


def plugin_languages():
    with open(os.path.join(HERE, "plugin-languages.txt"), encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.startswith("#")]


def first_param(maps, path, param, **must):
    for m in maps:
        r = m["request"]
        qp = r.get("queryParameters", {})
        if r["urlPath"] == path and param in qp and all(k in qp for k in must):
            return qp[param].get("equalTo")
    return None


def profile_keys(maps, languages):
    """(language, key) of the quality profiles of the project, from the recorded qualityprofiles/search."""
    for m in maps:
        r = m["request"]
        if r["urlPath"] == "/api/qualityprofiles/search.protobuf" and \
                r.get("queryParameters", {}).get("project", {}).get("equalTo") == PROJECT and m["response"]["status"] == 200:
            found = []
            for _, _, payload, _ in protowire.split_fields(body_bytes(m)):
                f = {}
                for t, w, p, raw in protowire.split_fields(payload):
                    n = protowire.read_varint(t, 0)[0] >> 3
                    f[n] = p if w == 2 else raw
                lang = f.get(3, b"").decode()
                if lang in languages:
                    found.append((lang, f[1].decode()))
            return found
    return []


def installed_plugin_keys(maps):
    for m in maps:
        if m["request"]["urlPath"] == "/api/plugins/installed" and m["response"]["status"] == 200:
            d = json.loads(m["response"].get("body") or "{}")
            return [p["key"] for p in d.get("plugins", []) if p.get("sonarLintSupported")]
    return []


MAX_PAGES = 20  # ServerApiHelper.MAX_PAGES


def _fields(body):
    """{field number: [(wire type, payload or raw bytes)]} of one message."""
    out = {}
    for t, w, p, raw in protowire.split_fields(body):
        out.setdefault(protowire.read_varint(t, 0)[0] >> 3, []).append((w, p if w == 2 else raw[len(t):]))
    return out


def _varint(fields, n):
    for w, v in fields.get(n, []):
        if w == 0:
            return protowire.read_varint(v, 0)[0]
    return 0


def _paging_total(fields, n):
    """Common.Paging.total (field 3) inside the message in field n."""
    for w, v in fields.get(n, []):
        if w == 2:
            return _varint(_fields(v), 3)
    return 0


class Paged:
    """How core reads one paged answer: where the total is, which list holds the items, and the 20-page limit.

    total: ("paging", n)  Common.Paging message in field n, total in its field 3
           ("top", n)     plain varint in field n
           a list of those: the first one present wins (rules: r.hasPaging() ? r.getPaging().getTotal() : r.getTotal())
    items: field number of the repeated item list, or ("map", outer, inner) for a map inside a message
    """

    def __init__(self, total, items, limit_to_twenty_pages=False):
        self.total = total if isinstance(total, list) else [total]
        self.items = items
        self.limit = limit_to_twenty_pages

    def read(self, body):
        f = _fields(body)
        total = 0
        for kind, n in self.total:
            if n in f:
                total = _paging_total(f, n) if kind == "paging" else _varint(f, n)
                break
        if isinstance(self.items, tuple):
            _, outer, inner = self.items
            count = sum(len(_fields(v).get(inner, [])) for w, v in f.get(outer, []) if w == 2)
        else:
            count = len(f.get(self.items, []))
        return total, count


# Paging layout of each answer (protobuf messages of core 10.12.1, Common.Paging: pageIndex=1, pageSize=2, total=3)
PAGED_ORGS = Paged(("paging", 2), 1)                              # Organizations.SearchWsResponse
PAGED_PROJECTS = Paged(("paging", 1), 2, limit_to_twenty_pages=True)  # Components.SearchWsResponse
PAGED_TREE = Paged(("paging", 1), 4)                              # Components.TreeWsResponse
PAGED_RULES_BY_PROFILE = Paged([("paging", 8), ("top", 1)], ("map", 5, 1))  # Rules.SearchResponse, actives map
PAGED_TAINT_RULES = Paged(("top", 1), 4)                          # Rules.SearchResponse.getTotal, rules list
PAGED_VULNS = Paged(("paging", 4), 6, limit_to_twenty_pages=True)  # Issues.SearchWsResponse
PAGED_HOTSPOTS = Paged(("paging", 1), 2)                          # Hotspots.SearchWsResponse
PAGE_GUARD = 1000  # this check gives up (and fails) if a call never ends


def core_cases(maps):
    """Yield (label, url, extras) for every call core makes. extras may hold headers, 'status' (expected), 'any_status' (the real server's answer is recorded as it is: error or not)."""
    langs = plugin_languages()
    O = "&organization=" + ORG          # appended when the connection has an organisation key
    yield "system status (ServerVersionAndStatusChecker, ServerInfoSynchronizer)", "api/system/status", {}
    yield "validate connection (AuthenticationApi)", "api/authentication/validate?format=json", {}
    yield "validate connection, made-up token", "api/authentication/validate?format=json", \
        {"headers": {"Authorization": BAD_TOKEN_HEADER}, "any_status": True, "wants_priority": True}
    yield "organisation by key (OrganizationApi.getOrganization)", \
        "api/organizations/search.protobuf?organizations=" + ORG, {"paged": PAGED_ORGS}
    yield "organisation by key, unknown organisation", \
        "api/organizations/search.protobuf?organizations=" + UNKNOWN_ORG, {"any_status": True, "paged": PAGED_ORGS}
    yield "organisations of the user (OrganizationApi.listUserOrganizations)", \
        "api/organizations/search.protobuf?member=true", {"paged": PAGED_ORGS}
    yield "all projects (ComponentApi.getAllProjects)", \
        "api/components/search.protobuf?qualifiers=TRK" + O, {"paged": PAGED_PROJECTS}
    yield "project settings (SettingsApi.getProjectSettings)", \
        "/api/settings/values.protobuf?component=" + PROJECT, {}
    yield "installed plugins (PluginsApi.getInstalled)", "/api/plugins/installed", {}
    for key in installed_plugin_keys(maps):
        yield "plugin download %s (PluginsApi.getPlugin)" % key, "api/plugins/download?plugin=" + key, {}
    yield "all file keys (ComponentApi.getAllFileKeys)", \
        "api/components/tree.protobuf?qualifiers=FIL,UTS&component=%s%s" % (PROJECT, O), {"paged": PAGED_TREE}
    yield "one project (ComponentApi.fetchComponent)", "api/components/show.protobuf?component=" + PROJECT, {}
    yield "one project, unknown project", "api/components/show.protobuf?component=" + UNKNOWN_PROJECT, \
        {"status": 404}
    yield "project branches (ProjectBranchesApi)", "/api/project_branches/list.protobuf?project=" + PROJECT, {}
    yield "quality profiles of the project (QualityProfileApi)", \
        "/api/qualityprofiles/search.protobuf?project=%s%s" % (PROJECT, O), {}
    yield "quality profiles, unknown project", \
        "/api/qualityprofiles/search.protobuf?project=%s%s" % (UNKNOWN_PROJECT, O), {"status": 404}
    for lang, key in profile_keys(maps, set(langs)):
        yield ("rules of the %s profile (RulesApi.getAllActiveRules)" % lang, "/api/rules/search.protobuf?qprofile=%s%s"
               "&activation=true&f=templateKey,actives&types=CODE_SMELL,BUG,VULNERABILITY,SECURITY_HOTSPOT&s=key" % (Q(key), O),
               {"paged": PAGED_RULES_BY_PROFILE})
    yield "taint rules (RulesApi.getAllTaintRules)", \
        "/api/rules/search.protobuf?repositories=%s%s&f=repo&s=key" % (",".join(Q(r) for r in TAINT_REPOS_IN_ENUM_ORDER), O), \
        {"paged": PAGED_TAINT_RULES}
    rule_key = first_param(maps, "/api/rules/show.protobuf", "key")
    if rule_key:
        yield "one rule (RulesApi.getRule)", "/api/rules/show.protobuf?key=%s%s" % (rule_key, O), {}
    vuln = "/api/issues/search.protobuf?statuses=OPEN,CONFIRMED,REOPENED,RESOLVED&types=VULNERABILITY&componentKeys=%s&rules=%%s&branch=%s%s" % (PROJECT, BRANCH, O)
    yield "taint vulnerabilities, no taint rules on the server (rules is empty)", vuln % "", {"paged": PAGED_VULNS}
    yield "taint vulnerabilities, with taint rules", vuln % Q("javasecurity:S3649,jssecurity:S5131"), {"paged": PAGED_VULNS}
    issue_key = first_param(maps, "/api/issues/search.protobuf", "issues", componentKeys=1) or \
        first_param(maps, "/api/issues/search.protobuf", "issues")
    if issue_key:
        yield "issue by key (IssueApi.searchByKey)", \
            "/api/issues/search.protobuf?issues=%s&additionalFields=transitions%s&ps=1&p=1" % (Q(issue_key), O), {}
        yield "issue by key and branch (IssueApi.fetchServerIssue)", \
            "/api/issues/search.protobuf?issues=%s&componentKeys=%s&ps=1&p=1&branch=%s" % (Q(issue_key), PROJECT, BRANCH), {}
    yield "issues of the project (ServerIssueUpdater, batch)", "/batch/issues?key=%s&branch=%s" % (PROJECT, BRANCH), {}
    yield "issues of one file (ServerIssueUpdater.updateFileIssues)", \
        "/batch/issues?key=%s&branch=%s" % (Q(PROJECT + ":force-app/main/default/classes/MyClass.cls"), BRANCH), {}
    yield "hotspots of the project (HotspotApi.getAll)", \
        "/api/hotspots/search.protobuf?projectKey=%s&branch=%s" % (PROJECT, BRANCH), {"paged": PAGED_HOTSPOTS}
    yield "hotspots of one file (HotspotApi.getFromFile)", \
        "/api/hotspots/search.protobuf?projectKey=%s&files=%s&branch=%s" % (PROJECT, Q("force-app/main/default/classes/MyClass.cls"), BRANCH), {"paged": PAGED_HOTSPOTS}
    hotspot_key = first_param(maps, "/api/hotspots/show.protobuf", "hotspot")
    if hotspot_key:
        yield "one hotspot (HotspotApi.show)", "/api/hotspots/show.protobuf?hotspot=" + Q(hotspot_key), {}
    for label, url in source_cases(maps):
        yield label, url, {}
    yield "new code definition, no branch (LocalStorageSynchronizer:85, SonarCloud kind: periods)", \
        "/api/measures/component.protobuf?additionalFields=periods&metricKeys=projects&component=" + PROJECT, {"any_status": True}
    yield "notifications (DevelopersApi.getEvents)", \
        "api/developers/search_events?projects=%s&from=%s" % (PROJECT, Q("2024-05-01T10:00:00+0000")), {}


def _recorded_body(maps, path, **params):
    """Body of the first 200 recording at this address whose query parameters include these names."""
    for m in maps:
        r = m["request"]
        if r["urlPath"] == path and m["response"]["status"] == 200 and "base64Body" in m["response"] and \
                all(k in r.get("queryParameters", {}) for k in params):
            return m["_file"], body_bytes(m)
    return None, None


def _strings(fields, n):
    return [v.decode("utf-8") for w, v in fields.get(n, []) if w == 2]


def source_cases(maps):
    """The /api/sources/raw addresses core 10.12.1 sends, with the file keys taken from the answers it reads.

    SourceApi encodes the key with URLEncoder (a space becomes '+').
      TaintIssueDownloader: every taint issue (Issues.SearchWsResponse field 6) with a text range (field 9) fetches
        its component (field 4), and every flow location (Flow field 1, Location) with a text range (field 2)
        fetches the location's component (field 4); no branch
      IssueApi.fetchServerIssue: the component of the first issue, if it is in the components list (field 7,
        Component.key field 2); with the branch
      HotspotApi.fetch (hotspots/show): the key (field 2) of the component (field 2); no branch
    The file of the sources recording itself is asked for too, with and without the branch.
    """
    key = lambda k: urllib.parse.quote_plus(k)  # noqa: E731
    seen = set()

    def once(label, url):
        if url not in seen:
            seen.add(url)
            yield label, url

    name, body = _recorded_body(maps, "/api/issues/search.protobuf", types=1, rules=1)
    taint_files = []
    for _, issue in _fields(body or b"").get(6, []):
        f = _fields(issue)
        if 9 in f:
            taint_files += _strings(f, 4)
        for _, flow in f.get(10, []):
            for _, loc in _fields(flow).get(1, []):
                lf = _fields(loc)
                if 2 in lf:
                    taint_files += _strings(lf, 4)
    for k in dict.fromkeys(taint_files):
        yield from once("source of a file of a taint vulnerability (%s, TaintIssueDownloader)" % name,
                        "/api/sources/raw?key=" + key(k))
    for params in ({"issues": 1, "componentKeys": 1, "branch": 1}, {"issues": 1}):
        name, body = _recorded_body(maps, "/api/issues/search.protobuf", **params)
        if not body:
            continue
        f = _fields(body)
        components = {c for _, comp in f.get(7, []) for c in _strings(_fields(comp), 2)}
        for _, issue in f.get(6, [])[:1]:
            for k in _strings(_fields(issue), 4):
                if k in components:
                    yield from once("source of the file of an issue opened from the browser (%s, "
                                    "IssueApi.fetchServerIssue)" % name,
                                    "/api/sources/raw?key=%s&branch=%s" % (key(k), BRANCH))
    name, body = _recorded_body(maps, "/api/hotspots/show.protobuf", hotspot=1)
    for _, comp in _fields(body or b"").get(2, []):
        for k in _strings(_fields(comp), 2):
            yield from once("source of the file of a hotspot opened from the browser (%s, HotspotApi.fetch)" % name,
                            "/api/sources/raw?key=" + key(k))
    for m in maps:
        k = m["request"].get("queryParameters", {}).get("key", {}).get("equalTo")
        if m["request"]["urlPath"] == "/api/sources/raw" and k:
            yield from once("source of the recorded file (%s, SourceApi)" % m["_file"], "/api/sources/raw?key=" + key(k))
            yield from once("source of the recorded file on a branch (%s, SourceApi)" % m["_file"],
                            "/api/sources/raw?key=%s&branch=%s" % (key(k), BRANCH))


def is_fallback_only(hits):
    """True if one recording wins on priority and every other one is marked as a fallback (metadata.fallback)."""
    best = hits[0].get("priority", 5)
    return all(h.get("metadata", {}).get("fallback") is True and h.get("priority", 5) > best for h in hits[1:])


def check_core_urls(maps, quiet):
    ok = True
    n = 0

    def say(line):
        if not quiet:
            print(line)

    def one(label, url, headers, extra):
        """Check one address. Returns (problem or None, matched mapping or None)."""
        hits = match(maps, url, headers)
        if not hits:
            return "no recording matches", None
        if extra.get("wants_priority"):
            if len(hits) < 2 or hits[0].get("priority", 5) >= hits[1].get("priority", 5):
                return "the made-up token recording must have a better priority than the normal one (%s)" % \
                    [h["_file"] for h in hits], hits[0]
        elif len(hits) != 1 and not is_fallback_only(hits):
            return "%d recordings match (%s)" % (len(hits), ", ".join(h["_file"] for h in hits)), hits[0]
        status = hits[0]["response"]["status"]
        if extra.get("any_status"):
            return None, hits[0]
        if "status" in extra and status != extra["status"]:
            return "answer is HTTP %s, expected %s" % (status, extra["status"]), hits[0]
        if "status" not in extra and status >= 400:
            return "the recording answers HTTP %s" % status, hits[0]
        return None, hits[0]

    for label, url, extra in core_cases(maps):
        headers = extra.get("headers")
        paged = extra.get("paged")
        if paged:
            # follow the pages the way ServerApiHelper.getPaginated / processPage does
            page = 1
            while True:
                purl = "%s%sps=%d&p=%d" % (url, "&" if "?" in url else "?", PAGE_SIZE, page)
                problem, m = one(label, purl, headers, extra)
                n += 1
                if not problem and m["response"]["status"] >= 400:
                    # an error answer (allowed by any_status): core throws, no next page
                    say("ok    %s, page %d (HTTP %s, core stops)\n      %s -> %s" %
                        (label, page, m["response"]["status"], purl, m["_file"]))
                    break
                stop = True
                if not problem:
                    try:
                        total, count = paged.read(body_bytes(m))
                        stop = count == 0 or (total > 0 and page * PAGE_SIZE >= total)
                        if not stop and paged.limit and page >= MAX_PAGES:
                            stop = True
                        if not stop and page >= PAGE_GUARD:
                            problem = "still asking for more pages after %d pages" % page
                    except ValueError as e:
                        problem = "the answer is not the expected protobuf message: %s" % e
                say("%s  %s, page %d\n      %s -> %s" % ("ok  " if not problem else "FAIL", label, page, purl,
                                                         m["_file"] if m else "-"))
                if problem:
                    print("FAIL %s, page %d: %s\n      %s" % (label, page, problem, purl))
                    ok = False
                    break
                if stop:
                    break
                page += 1
            continue
        problem, m = one(label, url, headers, extra)
        n += 1
        say("%s  %s\n      %s -> %s" % ("ok  " if not problem else "FAIL", label, url, m["_file"] if m else "-"))
        if problem:
            print("FAIL %s: %s\n      %s" % (label, problem, url))
            ok = False
    say("%d core address(es) checked" % n)
    return ok


def check_streams(maps, quiet):
    ok = True
    streams = singles = 0
    for m in maps:
        res = m["response"]
        path = m["request"]["urlPath"]
        if "base64Body" not in res:
            if path in STREAM_PATHS and not quiet:
                print("skip  %s %s: the answer is text (HTTP %s %s), not a protobuf stream" %
                      (m["_file"], path, res["status"], res.get("headers", {}).get("Content-Type", "")))
            continue
        body = base64.b64decode(res["base64Body"])
        if not body:
            continue
        try:
            if path in STREAM_PATHS:
                msgs = protowire.split_delimited(body)
                streams += 1
                if not quiet:
                    print("ok    %s %s: stream of %d message(s), %d bytes" % (m["_file"], path, len(msgs), len(body)))
            elif path.endswith(".protobuf"):
                protowire.split_fields(body)
                singles += 1
        except ValueError as e:
            print("FAIL  %s %s: %s" % (m["_file"], path, e))
            ok = False
            continue
        if res.get("status", 200) < 400:
            for problem in protoschema.body_problems(path, body):
                print("FAIL  %s %s: by core's .proto files, %s" % (m["_file"], path, problem))
                ok = False
    if not quiet:
        print("%d stream(s) and %d single message(s) parse" % (streams, singles))
    return ok


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    quiet = "--quiet" in argv
    if len(args) != 2 or args[1] not in ("core-urls", "streams", "all"):
        print(__doc__, file=sys.stderr)
        return 64
    maps = load_mappings(args[0])
    if not maps:
        print("FAIL no recordings in %s" % args[0])
        return 1
    ok = True
    if args[1] in ("streams", "all"):
        ok = check_streams(maps, quiet) and ok
    if args[1] in ("core-urls", "all"):
        ok = check_core_urls(maps, quiet) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
