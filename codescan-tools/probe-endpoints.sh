#!/usr/bin/env bash
# Probe the web addresses that sonarlint-core 10.12.1 calls against a real CodeScan server.
# Read-only: every request is a GET. The token is never printed, never put on a command line
# and never written to a recording.
#
# Settings (environment):
#   CODESCAN_TEST_URL      required  server address, e.g. https://<codescan-server>
#   CODESCAN_TEST_ORG      required  organisation key
#   CODESCAN_TEST_TOKEN    required  access token
#   CODESCAN_TEST_PROJECT  optional  project key; without it project-level probes are skipped
#   CODESCAN_SCRUB_USERS / CODESCAN_SCRUB_EXTRA   passed to scrub-recording.py (see there)
#   PROBE_CONNECT_TIMEOUT (default 5)  PROBE_MAX_TIME (default 30)  seconds, for every request
#   PROBE_RESOLVE          optional curl --resolve value (host:port:ip), for when the system DNS cannot find the host
#   PROBE_KEEP_RAW=1       keep the raw (unscrubbed) answers in the scratch folder, for inspection
#   PROBE_TMPDIR           scratch parent folder (default /tmp/cs-t3)
#
# Options:
#   --record DIR    write scrubbed WireMock mapping files to DIR
#   --table FILE    also write the result table (markdown) to FILE
#   --hidden-list FILE  write the values that were hidden in the recordings (user names, other organisations and
#                   projects, server id, login hosts) to FILE, one per line, mode 600. Keep it out of git. The
#                   committed-recordings check reads it, so a real value that slips into a recording is found.
#
# Exit codes: 0 done, 2 server unreachable, 3 token rejected (Bearer and Basic), 64 bad settings,
#             1 scrub or recording step failed, 4 a call that should be recorded got no answer (the old
#             recordings are then left as they are).
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECORD_DIR=""
TABLE_FILE=""
HIDDEN_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --record) RECORD_DIR="${2:-}"; shift 2 ;;
    --table)  TABLE_FILE="${2:-}"; shift 2 ;;
    --hidden-list) HIDDEN_FILE="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,27p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 64 ;;
  esac
done

URL="${CODESCAN_TEST_URL:-}"
ORG="${CODESCAN_TEST_ORG:-}"
TOKEN="${CODESCAN_TEST_TOKEN:-}"
PROJECT="${CODESCAN_TEST_PROJECT:-}"
CT="${PROBE_CONNECT_TIMEOUT:-5}"
MT="${PROBE_MAX_TIME:-30}"

if [ -z "$URL" ] || [ -z "$ORG" ] || [ -z "$TOKEN" ]; then
  echo "probe-endpoints: set CODESCAN_TEST_URL, CODESCAN_TEST_ORG and CODESCAN_TEST_TOKEN" >&2
  exit 64
fi
URL="${URL%/}"

B64_TOKEN_COLON="$(printf '%s:' "$TOKEN" | base64 | tr -d '\n')"
B64_TOKEN="$(printf '%s' "$TOKEN" | base64 | tr -d '\n')"

# Everything that is printed goes through here: token (plain and base64), organisation and project are hidden.
MASK_REAL=()
mask() {
  local s="$1" r
  for r in ${MASK_REAL[@]+"${MASK_REAL[@]}"}; do s="${s//"$r"/***}"; done
  s="${s//"$TOKEN"/***}"
  s="${s//"$B64_TOKEN_COLON"/***}"
  s="${s//"$B64_TOKEN"/***}"
  s="${s//"$ORG"/test-org}"
  [ -n "$PROJECT" ] && s="${s//"$PROJECT"/test-project}"
  if [ -f "${MASK_SED:-}" ]; then s="$(printf '%s' "$s" | sed -E -f "$MASK_SED")"; fi
  printf '%s' "$s"
}
say() { printf '%s\n' "$(mask "$1")"; }

SCRATCH_PARENT="${PROBE_TMPDIR:-/tmp/cs-t3}"
mkdir -p "$SCRATCH_PARENT" && chmod 700 "$SCRATCH_PARENT" 2>/dev/null
WORK="$(mktemp -d "$SCRATCH_PARENT/probe.XXXXXX")" || { echo "probe-endpoints: cannot create scratch folder" >&2; exit 1; }
chmod 700 "$WORK"
cleanup() {
  rm -f "$WORK/auth.hdr"
  [ "${PROBE_KEEP_RAW:-0}" = "1" ] || rm -rf "$WORK"
}
trap cleanup EXIT
RAW="$WORK/raw"
mkdir -p "$RAW"
# sed script used by mask(): e-mail addresses, and short user logins (only as whole words, see add_short_mask)
MASK_SED="$WORK/mask.sed"
printf '%s\n' 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/user@example.test/g' > "$MASK_SED"
add_short_mask() {
  local esc; esc="$(printf '%s' "$1" | sed 's/[][\.*^$/+?(){}|]/\\&/g')"
  local line="s/(^|[^A-Za-z0-9_-])$esc([^A-Za-z0-9_-]|\$)/\\1***\\2/g"
  printf '%s\n%s\n' "$line" "$line" >> "$MASK_SED"
}
TABLE="$WORK/table.md"
: > "$TABLE"

# The language keys the plugin can enable (one per line in plugin-languages.txt, comments allowed)
PROBE_ENABLED_LANGS="$(sed -e 's/#.*//' -e 's/[[:space:]]//g' -e '/^$/d' "$HERE/plugin-languages.txt" | paste -sd, -)"
export PROBE_ENABLED_LANGS
urlenc() { python3 -I -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }
ORG_E="$(urlenc "$ORG")"
PROJECT_E=""
[ -n "$PROJECT" ] && PROJECT_E="$(urlenc "$PROJECT")"

# JSON helper: json_get <file> <python expression over d>, prints value or nothing.
json_get() {
  python3 -I -c '
import json, os, sys
try:
    d = json.load(open(sys.argv[1]))
    v = eval(sys.argv[2], {"d": d, "ENABLED": [l for l in os.environ.get("PROBE_ENABLED_LANGS", "").split(",") if l]})
    if v is not None:
        print(v)
except Exception:
    pass' "$1" "$2" 2>/dev/null
}

# fetch <name> <path-with-query> <accept> [extra curl args...]
# Sets F_STATUS F_CTYPE F_BODY F_CURL_RC. Uses AUTH_HEADER_ARGS.
AUTH_HEADER_ARGS=()
RESOLVE_ARGS=()
[ -n "${PROBE_RESOLVE:-}" ] && RESOLVE_ARGS=(--resolve "$PROBE_RESOLVE")
fetch() {
  local name="$1" path="$2" accept="$3"; shift 3
  F_BODY="$RAW/$name.body"
  local hdr="$RAW/$name.hdr"
  F_STATUS="$(curl -sS --connect-timeout "$CT" --max-time "$MT" --retry 1 --retry-delay 1 \
      ${RESOLVE_ARGS[@]+"${RESOLVE_ARGS[@]}"} ${AUTH_HEADER_ARGS[@]+"${AUTH_HEADER_ARGS[@]}"} -H "Accept: $accept" \
      -D "$hdr" -o "$F_BODY" -w '%{http_code}' "$@" "$URL/$path" 2>"$RAW/$name.err")"
  F_CURL_RC=$?
  [ -f "$F_BODY" ] || : > "$F_BODY"
  F_CTYPE=""
  if [ -f "$hdr" ]; then
    F_CTYPE="$(grep -i '^content-type:' "$hdr" | tail -1 | sed 's/^[^:]*: *//' | tr -d '\r\n')"
  fi
}

sample_of() {
  case "$2" in
    *json*|*text*|*xml*|"") head -c 600 "$1" | LC_ALL=C tr '\r\n\t' '   ' | LC_ALL=C tr -c '[:print:]' '.' ;;
    *) printf '<binary, %s bytes>' "$(wc -c < "$1" | tr -d ' ')" ;;
  esac
}

# Probes whose address core 10.12.1 never calls for a SonarCloud-type connection (checked against the core source).
# They are still probed, to learn what the server answers, and the table says so.
NOT_CALLED="features-list server-version orgs-json components-search-json search-projects settings-global settings-json
 rules-json-clean-code project-branches-json qprofiles-json tree-json issues-json hotspots-search-json
 issues-pull issues-pull-taint hotspots-pull push-events measures-new-code-period"
N=0
OK_COUNT=0
NO_COUNT=0
NO_ANSWER=""   # ids of calls that should be recorded but got no answer at all
# Optional settings a caller can put in front of a probe call (they last for that call only):
#   REC_NOTE    text for the mapping's metadata.note ({status} and {ctype} are replaced by the real answer)
#   REC_IGNORE  comma list of query parameters the mapping must not match on
#   REC_ANY     comma list of query parameters the mapping matches with any value
#   REC_HEADER  "Name=value": the mapping only matches a request that carries this header
#   REC_PRIORITY  WireMock priority of the mapping (lower number wins; the default is 5)
# record is: yes | no | error-only (record only if the status is 400 or more) | standin (see below)
# probe <name> <path> <accept> <record> [extra curl args...]  -> prints and tables one row
probe() {
  local name="$1" path="$2" accept="$3" record="$4"; shift 4
  N=$((N + 1))
  local id; id="$(printf '%02d-%s' "$N" "$name")"
  fetch "$id" "$path" "$accept" "$@"
  local supported status
  status="$F_STATUS"
  if [ "$F_CURL_RC" -ne 0 ] && { [ -z "$status" ] || [ "$status" = "000" ]; }; then
    status="ERR$F_CURL_RC"; supported="no (no answer)"; NO_COUNT=$((NO_COUNT + 1))
    [ "$record" != "no" ] && NO_ANSWER="$NO_ANSWER $id"
  elif [ "$status" -ge 200 ] 2>/dev/null && [ "$status" -lt 300 ]; then
    case "$F_CTYPE" in
      text/html*) supported="no (HTML page, not an API answer)"; NO_COUNT=$((NO_COUNT + 1)) ;;
      *) supported="yes"; OK_COUNT=$((OK_COUNT + 1)) ;;
    esac
  else
    supported="no"; NO_COUNT=$((NO_COUNT + 1))
  fi
  case " ${NOT_CALLED//$'\n'/ } " in *" $name "*) supported="$supported (not called by core)" ;; esac
  local sample; sample="$(sample_of "$F_BODY" "$F_CTYPE")"
  sample="$(mask "$sample")"; sample="${sample:0:110}"
  local shown_path="$path"
  local row="| $id | \`/$shown_path\` | $status | ${F_CTYPE%%;*} | \`$(printf '%s' "$sample" | tr '|`' '  ')\` | $supported |"
  say "$row" | tee -a "$TABLE"
  LAST_ID="$id"; LAST_STATUS="$status"; LAST_BODY="$F_BODY"
  local do_record="no"
  case "$record" in
    yes) do_record="yes" ;;
    error-only) [ "$status" -ge 400 ] 2>/dev/null && do_record="yes" ;;
    standin) [ "$status" -ge 200 ] 2>/dev/null && [ "$status" -lt 300 ] && do_record="standin" ;;
  esac
  if [ "$do_record" != "no" ] && [ "$status" -ge 100 ] 2>/dev/null; then
    python3 -I - "$RAW" "$id" "$path" "$status" "$F_CTYPE" "$do_record" "${REC_NOTE:-}" "${REC_IGNORE:-}" "${REC_ANY:-}" "${REC_HEADER:-}" "${REC_PRIORITY:-}" <<'PY'
import io, json, sys, zipfile
raw, id_, path, status, ctype, mode, note, ignore, anyv, header, priority = sys.argv[1:12]
note = note.replace("{status}", status).replace("{ctype}", ctype or "none")
meta = {"path": path, "status": int(status), "contentType": ctype, "bodyFile": id_ + ".body"}
if mode == "standin":
    # A tiny jar made here (fixed timestamp, so reruns give the same bytes). The real plugin is not stored.
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        zi = zipfile.ZipInfo("META-INF/MANIFEST.MF", date_time=(2024, 1, 1, 0, 0, 0))
        z.writestr(zi, "Manifest-Version: 1.0\nStand-In: true\n")
    open(raw + "/" + id_ + ".standin.jar", "wb").write(buf.getvalue())
    meta.update({"status": 200, "bodyFile": id_ + ".standin.jar",
                 "contentType": ctype if ctype.startswith("application/") else "application/java-archive"})
if note:
    meta["note"] = note
if ignore:
    meta["ignoreParams"] = ignore.split(",")
if anyv:
    meta["anyValueParams"] = anyv.split(",")
if header:
    name, value = header.split("=", 1)
    meta["requestHeaders"] = {name: value}
if priority:
    meta["priority"] = int(priority)
json.dump(meta, open(raw + "/" + id_ + ".meta.json", "w"))
PY
  fi
}

# probe_paged <name> <path without paging> <accept>: records page 1, 2, ... until the server's total is covered
# (core does the same for rules; the cap is MAX_PAGES pages of 500, with a warning).
# Only for rules/search.protobuf, whose answer starts with total (field 1), p (field 2), ps (field 3).
MAX_PAGES=50
proto_total_ps() { # prints "total ps" read from the start of a protobuf body
  python3 -I - "$1" <<'PY'
import sys
b = open(sys.argv[1], "rb").read()
i = 0
vals = {}
def varint(i):
    r = s = 0
    while True:
        c = b[i]; i += 1; r |= (c & 127) << s; s += 7
        if c < 128:
            return r, i
try:
    while i < len(b):
        t, i = varint(i)
        f, w = t >> 3, t & 7
        if w == 0:
            v, i = varint(i); vals[f] = v
        elif w == 2:
            n, i = varint(i); i += n
        elif w == 1:
            i += 8
        elif w == 5:
            i += 4
        else:
            break
        if f >= 3:
            break
except IndexError:
    pass
print(vals.get(1, 0), vals.get(3, 0))
PY
}
probe_paged() {
  local name="$1" base="$2" accept="$3" pg=1 total ps
  while [ "$pg" -le "$MAX_PAGES" ]; do
    probe "$name" "$base&ps=500&p=$pg" "$accept" yes
    [ "$LAST_STATUS" = "200" ] || break
    read -r total ps < <(proto_total_ps "$LAST_BODY")
    [ "${ps:-0}" -gt 0 ] 2>/dev/null || break
    [ $((pg * ps)) -ge "${total:-0}" ] && break
    pg=$((pg + 1))
  done
  [ "$pg" -gt "$MAX_PAGES" ] && say "WARNING: $name has more than $MAX_PAGES pages (500 entries each); pages after $MAX_PAGES are not recorded"
  return 0
}
# warn_if_paged <label> <json file>: the JSON twin of a recorded list tells how many entries there are
warn_if_paged() {
  local n; n="$(json_get "$2" 'd["paging"]["total"] if "paging" in d else d.get("total", 0)')"
  if [ "${n:-0}" -gt 500 ] 2>/dev/null; then
    say "WARNING: $1 has $n entries (more than one page of 500), but only page 1 is recorded"
  fi
}

skip_row() {
  N=$((N + 1))
  say "$(printf '| %02d-%s | - | - | - | - | skipped: %s |' "$N" "$1" "$2")" | tee -a "$TABLE"
}

note_row() {
  say "$(printf '| - | %s | - | - | %s | not probed |' "$1" "$2")" | tee -a "$TABLE"
}

say "Probing $URL  (organisation: $ORG, project: ${PROJECT:-none}, timeouts: connect ${CT}s / total ${MT}s)"
AUTH_HEADER_ARGS=()
fetch "reach" "api/system/status" "application/json"
if [ "$F_CURL_RC" -ne 0 ] && { [ -z "$F_STATUS" ] || [ "$F_STATUS" = "000" ]; }; then
  say "probe-endpoints: server unreachable ($URL), curl exit $F_CURL_RC: $(head -c 200 "$RAW/reach.err")" >&2
  exit 2
fi
say "Server answered without credentials: HTTP $F_STATUS"

write_header_file() {
  ( umask 077; printf 'Authorization: %s\n' "$1" > "$WORK/auth.hdr" )
}
auth_ok() { # after fetch: 2xx and not an explicit "valid":false
  [ "$F_STATUS" -ge 200 ] 2>/dev/null && [ "$F_STATUS" -lt 300 ] && ! grep -q '"valid" *: *false' "$F_BODY"
}
AUTH_STYLE=""
write_header_file "Bearer $TOKEN"
AUTH_HEADER_ARGS=(-H "@$WORK/auth.hdr")
fetch "auth-bearer" "api/authentication/validate?format=json" "application/json"
BEARER_STATUS="$F_STATUS"
if auth_ok; then
  AUTH_STYLE="bearer"
elif [ "$BEARER_STATUS" = "401" ] || [ "$BEARER_STATUS" = "403" ]; then
  write_header_file "Basic $B64_TOKEN_COLON"
  fetch "auth-basic" "api/authentication/validate?format=json" "application/json"
  if auth_ok; then AUTH_STYLE="basic"; fi
fi
if [ -z "$AUTH_STYLE" ]; then
  say "probe-endpoints: token rejected (Bearer got HTTP $BEARER_STATUS, Basic got HTTP ${F_STATUS:-none})" >&2
  exit 3
fi
say "Auth style that works: $AUTH_STYLE (Bearer answered HTTP $BEARER_STATUS)"
# Names that must not reach a recording or the screen: the token owner and the other organisations they belong to.
NL=$'\n'
USER_NAMES=()
SHORT_NAMES=()
OTHER_ORGS=()
# add_user <value>: a user login or display name found in an answer. 4 or more characters: replaced wherever it
# appears. Shorter: replaced only where it is a whole value (see CODESCAN_SCRUB_EXACT in scrub-recording.py).
add_user() {
  local v="$1" u
  [ -n "$v" ] || return 0
  if [ "$v" = "$ORG" ] || [ "$v" = "$PROJECT" ]; then return 0; fi
  for u in ${USER_NAMES[@]+"${USER_NAMES[@]}"} ${SHORT_NAMES[@]+"${SHORT_NAMES[@]}"}; do
    [ "$u" = "$v" ] && return 0
  done
  if [ "${#v}" -ge 4 ]; then
    USER_NAMES+=("$v")
  else
    SHORT_NAMES+=("$v"); add_short_mask "$v"
  fi
}
# users_in <json file>: every user login and display name in an answer (assignee, author, comment writers,
# changelog users, user lists), one per line. It reads the JSON twin of an answer, so fields the protobuf
# schema does not name are seen too.
users_in() {
  python3 -I - "$1" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    sys.exit(0)
KEYS = {"assignee", "author", "user", "userName", "login", "authorLogin", "creator", "reporter",
        "assigneeName", "authorName", "createdBy", "updatedBy"}
found = []
def add(v):
    if isinstance(v, str) and v.strip() and v not in found:
        found.append(v)
def walk(x):
    if isinstance(x, dict):
        if isinstance(x.get("login"), str):
            add(x["login"])
            add(x.get("name"))
        for k, v in x.items():
            if k in KEYS:
                add(v)
            walk(v)
    elif isinstance(x, list):
        for y in x:
            walk(y)
walk(d)
print("\n".join(found))
PY
}
collect_users_from() { local v; while IFS= read -r v; do add_user "$v"; done < <(users_in "$1"); }
# paged_json <name> <path without paging>: fetches every page (500 entries each, up to MAX_PAGES) of a JSON list
# answer. Leaves the page bodies in $RAW/<name>-<page>.body and sets PAGED_FIRST to the first one.
paged_json() {
  local name="$1" base="$2" pg=1 total
  PAGED_FIRST=""
  while [ "$pg" -le "$MAX_PAGES" ]; do
    fetch "$name-$pg" "$base&ps=500&p=$pg" "application/json"
    [ -n "$PAGED_FIRST" ] || PAGED_FIRST="$F_BODY"
    printf '%s\n' "$F_BODY" >> "$WORK/paged.list"
    total="$(json_get "$F_BODY" 'd["paging"]["total"] if "paging" in d else 0')"
    [ $((pg * 500)) -ge "${total:-0}" ] 2>/dev/null && return 0
    pg=$((pg + 1))
  done
  say "WARNING: $name has more than $MAX_PAGES pages (500 entries each); pages after $MAX_PAGES are not read"
}

fetch "me" "api/users/current" "application/json"
for v in "$(json_get "$F_BODY" 'd["login"]')" "$(json_get "$F_BODY" 'd["name"]')"; do add_user "$v"; done
: > "$WORK/paged.list"
paged_json orgs-all "api/organizations/search?member=true"
while IFS= read -r f; do
  while IFS= read -r v; do
    [ -n "$v" ] && [ "$v" != "$ORG" ] && OTHER_ORGS+=("$v")
  done < <(json_get "$f" '"\n".join([o["key"] for o in d["organizations"]] + [o["name"] for o in d["organizations"]])')
done < "$WORK/paged.list"
OTHER_PROJECTS=()
: > "$WORK/paged.list"
paged_json projects-all "api/components/search?qualifiers=TRK&organization=$ORG_E"
while IFS= read -r f; do
  while IFS= read -r v; do
    [ -n "$v" ] && [ "$v" != "$PROJECT" ] && [ "$v" != "$ORG" ] && [ "$v" != "main" ] && [ "$v" != "master" ] && OTHER_PROJECTS+=("$v")
  done < <(json_get "$f" '"\n".join(sorted({x for c in d["components"] for x in (c.get("key", ""), c.get("name", "")) if len(x) >= 4}))')
done < "$WORK/paged.list"
# Main branch, and every user named in the project's issues (all pages), the first issue's change log, the
# project's hotspots (all pages) and the first hotspot's details.
BRANCH="main"
if [ -n "$PROJECT_E" ]; then
  fetch "branches-early" "api/project_branches/list?project=$PROJECT_E" "application/json"
  b="$(json_get "$F_BODY" '[b["name"] for b in d["branches"] if b.get("isMain")][0]')"
  [ -n "$b" ] && BRANCH="$b"
  : > "$WORK/paged.list"
  paged_json users-issues "api/issues/search?componentKeys=$PROJECT_E&organization=$ORG_E&additionalFields=_all"
  FIRST_ISSUE="$(json_get "$PAGED_FIRST" 'd["issues"][0]["key"]')"
  while IFS= read -r f; do collect_users_from "$f"; done < "$WORK/paged.list"
  if [ -n "$FIRST_ISSUE" ]; then
    fetch "users-changelog" "api/issues/changelog?issue=$(urlenc "$FIRST_ISSUE")" "application/json"
    collect_users_from "$F_BODY"
  fi
  : > "$WORK/paged.list"
  paged_json users-hotspots "api/hotspots/search?projectKey=$PROJECT_E&branch=$(urlenc "$BRANCH")"
  FIRST_HOTSPOT="$(json_get "$PAGED_FIRST" 'd["hotspots"][0]["key"]')"
  while IFS= read -r f; do collect_users_from "$f"; done < "$WORK/paged.list"
  if [ -n "$FIRST_HOTSPOT" ]; then
    fetch "users-hotspot-show" "api/hotspots/show?hotspot=$(urlenc "$FIRST_HOTSPOT")" "application/json"
    collect_users_from "$F_BODY"
  fi
fi
fetch "server-id" "api/system/status" "application/json"
SERVER_ID="$(json_get "$F_BODY" 'd["id"]')"
SERVER_HOST="${URL#*://}"; SERVER_HOST="${SERVER_HOST%%/*}"
# Login-provider hosts: host names in setting values (other than the server's own) and in the page that
# batch/issues answers with. They must not reach a recording, so they are collected before anything is shown.
hosts_in() { # hosts_in <file> <settings|page>: prints host names, one per line
  python3 -I - "$1" "$2" "${SERVER_HOST%%:*}" <<'PY'
import json, re, sys
path, kind, server = sys.argv[1:4]
try:
    text = open(path, encoding="utf-8", errors="ignore").read()
except OSError:
    sys.exit(0)
found = set()
url = re.compile(r"https?://([A-Za-z0-9.-]+)")
bare = re.compile(r"^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")
def strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, list):
        for y in x:
            yield from strings(y)
    elif isinstance(x, dict):
        for y in x.values():
            yield from strings(y)
if kind == "settings":
    try:
        settings = json.loads(text).get("settings", [])
    except ValueError:
        settings = []
    for st in settings:
        for v in strings({k: st[k] for k in st if k != "key"}):
            found.update(url.findall(v))
            if st.get("key", "").startswith("sonar.auth.") and bare.match(v.strip()):
                found.add(v.strip())
else:
    found.update(url.findall(text))
skip = {server, "localhost", "www.w3.org", "schema.org"}
for h in sorted(found):
    h = h.strip(".")
    if len(h) >= 4 and h not in skip and not h.endswith(".example.test"):
        print(h)
PY
}
AUTH_HOSTS=()
fetch "settings-all" "api/settings/values" "application/json"
while IFS= read -r v; do [ -n "$v" ] && AUTH_HOSTS+=("$v"); done < <(hosts_in "$F_BODY" settings)
if [ -n "$PROJECT_E" ]; then
  fetch "login-page" "batch/issues?key=$PROJECT_E" "application/x-protobuf"
  while IFS= read -r v; do
    [ -n "$v" ] && AUTH_HOSTS+=("$v")
  done < <(hosts_in "$F_BODY" page)
fi
# Lists for the scrub step: one entry per line (a display name may hold a comma). A list given in the
# environment as a comma list is turned into lines first.
SCRUB_USERS=""; SCRUB_EXTRA=""; SCRUB_EXACT=""
while IFS= read -r v; do [ -n "$v" ] && SCRUB_USERS="$SCRUB_USERS$v$NL"; done < <(printf '%s' "${CODESCAN_SCRUB_USERS:-}" | tr ',' '\n')
for v in ${USER_NAMES[@]+"${USER_NAMES[@]}"}; do SCRUB_USERS="$SCRUB_USERS$v$NL"; MASK_REAL+=("$v"); done
for v in ${SHORT_NAMES[@]+"${SHORT_NAMES[@]}"}; do SCRUB_EXACT="$SCRUB_EXACT$v$NL"; done
while IFS= read -r v; do [ -n "$v" ] && SCRUB_EXTRA="$SCRUB_EXTRA$v$NL"; done < <(printf '%s' "${CODESCAN_SCRUB_EXTRA:-}" | tr ',' '\n')
i=0
for v in ${OTHER_ORGS[@]+"${OTHER_ORGS[@]}"}; do
  i=$((i + 1)); SCRUB_EXTRA="$SCRUB_EXTRA$v=other-org-$i$NL"; MASK_REAL+=("$v")
done
i=0
for v in ${OTHER_PROJECTS[@]+"${OTHER_PROJECTS[@]}"}; do
  i=$((i + 1)); SCRUB_EXTRA="$SCRUB_EXTRA$v=other-project-$i$NL"; MASK_REAL+=("$v")
done
i=0
for v in ${AUTH_HOSTS[@]+"${AUTH_HOSTS[@]}"}; do
  i=$((i + 1)); SCRUB_EXTRA="$SCRUB_EXTRA$v=auth-host-$i.example.test$NL"; MASK_REAL+=("$v")
done
[ -n "$SERVER_ID" ] && { SCRUB_EXTRA="$SCRUB_EXTRA$SERVER_ID=server-id$NL"; MASK_REAL+=("$SERVER_ID"); }
SCRUB_EXTRA="$SCRUB_EXTRA$SERVER_HOST=codescan.example.test$NL"
say "Hiding in recordings: ${#USER_NAMES[@]} user name(s) (and ${#SHORT_NAMES[@]} short one(s), hidden as whole values only), ${#OTHER_ORGS[@]} other organisation name(s), ${#OTHER_PROJECTS[@]} other project name(s), ${#AUTH_HOSTS[@]} login host(s), server id and host name"
if [ -n "$HIDDEN_FILE" ]; then
  rm -f "$HIDDEN_FILE"
  ( umask 077
    for v in ${USER_NAMES[@]+"${USER_NAMES[@]}"} ${SHORT_NAMES[@]+"${SHORT_NAMES[@]}"} ${OTHER_ORGS[@]+"${OTHER_ORGS[@]}"} \
             ${OTHER_PROJECTS[@]+"${OTHER_PROJECTS[@]}"} ${AUTH_HOSTS[@]+"${AUTH_HOSTS[@]}"}; do printf '%s\n' "$v"; done > "$HIDDEN_FILE"
    [ -n "$SERVER_ID" ] && printf '%s\n' "$SERVER_ID" >> "$HIDDEN_FILE"
    true )
  chmod 600 "$HIDDEN_FILE"
fi
{
  echo "Auth style that works: **$AUTH_STYLE** (Bearer answered HTTP $BEARER_STATUS)."
  echo
  echo "| # | address as sent | HTTP | content type | body sample | supported? |"
  echo "|---|---|---|---|---|---|"
} > "$TABLE"

# CodeScan keys: Apex is "sf", Visualforce "vf", Salesforce metadata "sfmeta" (not "apex"); HTML is "web".
ALL_LANGS="java,js,ts,py,php,xml,web,css,go,json,yaml,text,kotlin,ruby,scala,ipynb,cs,vbnet,sf,vf,sfmeta"
PS="ps=500&p=1"
probe status           "api/system/status" "application/json" yes
probe auth-validate    "api/authentication/validate?format=json" "application/json" yes
# What a rejected token looks like: a made-up token (never a real one), recorded with a better priority so it
# only wins when the request carries exactly this made-up token.
( umask 077; printf 'Authorization: Bearer REDACTED\n' > "$WORK/bad.hdr" )
AUTH_HEADER_ARGS=(-H "@$WORK/bad.hdr")
REC_HEADER="Authorization=Bearer REDACTED" REC_PRIORITY=1 \
  REC_NOTE="Answer to a made-up token (the word REDACTED, not a real token). Matches only a request that sends exactly Authorization: Bearer REDACTED; priority 1 so it wins over the normal answer for the same address." \
  probe auth-bad-token "api/authentication/validate?format=json" "application/json" yes
AUTH_HEADER_ARGS=(-H "@$WORK/auth.hdr")
probe features-list    "api/features/list" "application/json" yes
probe server-version   "api/server/version" "text/plain" no
probe orgs-by-key      "api/organizations/search.protobuf?organizations=$ORG_E&$PS" "application/x-protobuf" yes
probe orgs-member      "api/organizations/search.protobuf?member=true&$PS" "application/x-protobuf" yes
probe orgs-json        "api/organizations/search?member=true&ps=5&p=1" "application/json" no
warn_if_paged orgs-member "$LAST_BODY"
probe components-search "api/components/search.protobuf?qualifiers=TRK&organization=$ORG_E&$PS" "application/x-protobuf" yes
probe components-search-json "api/components/search?qualifiers=TRK&organization=$ORG_E&ps=5&p=1" "application/json" no
warn_if_paged components-search "$LAST_BODY"
probe search-projects  "api/components/search_projects?organization=$ORG_E&ps=5&p=1" "application/json" no
probe plugins-installed "api/plugins/installed" "application/json" yes
PLUGINS_BODY="$LAST_BODY"
probe settings-global  "api/settings/values.protobuf?keys=sonar.multi-quality-mode.enabled" "application/x-protobuf" yes
probe settings-json    "api/settings/values" "application/json" no
# Core builds repositories= from the SonarLanguage enum, in this order. The recording matches any value, so the
# order does not matter to the test.
REC_ANY=repositories REC_NOTE="Core asks for the taint repositories in SonarLanguage enum order; this recording matches any repositories value." \
  probe_paged taint-rules "api/rules/search.protobuf?repositories=roslyn.sonaranalyzer.security.cs,javasecurity,jssecurity,phpsecurity,pythonsecurity,tssecurity&organization=$ORG_E&f=repo&s=key" "application/x-protobuf"
probe rules-json-clean-code "api/rules/search?organization=$ORG_E&ps=2&p=1" "application/json" no
RULES_JSON="$LAST_BODY"
RULE_KEY="$(json_get "$RULES_JSON" 'd["rules"][0]["key"]')"
CLEAN_FIELDS="no"
if [ -n "$RULE_KEY" ] && grep -q '"cleanCodeAttribute"' "$RULES_JSON" && grep -q '"impacts"' "$RULES_JSON"; then CLEAN_FIELDS="yes"; fi
say "api/rules/search carries cleanCodeAttribute and impacts: $CLEAN_FIELDS"
if [ -n "$RULE_KEY" ]; then
  probe rule-show      "api/rules/show.protobuf?key=$(urlenc "$RULE_KEY")&organization=$ORG_E" "application/x-protobuf" yes
else
  skip_row rule-show "no rule key from rules/search"
fi

if [ -z "$PROJECT" ]; then
  skip_row project-probes "CODESCAN_TEST_PROJECT not set"
else
  probe component-show "api/components/show.protobuf?component=$PROJECT_E" "application/x-protobuf" yes
  probe project-branches "api/project_branches/list.protobuf?project=$PROJECT_E" "application/x-protobuf" yes
  probe project-branches-json "api/project_branches/list?project=$PROJECT_E" "application/json" no
  BRANCH_E="$(urlenc "$BRANCH")"
  say "Main branch used below: $BRANCH"

  probe qprofiles-json "api/qualityprofiles/search?project=$PROJECT_E&organization=$ORG_E" "application/json" no
  # One profile per language (the default one if there are several) for every language the plugin can enable
  # (plugin-languages.txt). Core downloads the rules of exactly these profiles. No cap.
  QP_KEYS="$(json_get "$LAST_BODY" '"\n".join(sum([[p["key"] for p in sorted([p for p in d["profiles"] if p["language"] == l], key=lambda p: not p.get("isDefault"))[:1]] for l in ENABLED], []))')"
  probe qprofiles      "api/qualityprofiles/search.protobuf?project=$PROJECT_E&organization=$ORG_E" "application/x-protobuf" yes
  if [ -n "$QP_KEYS" ]; then
    for QP_KEY in $QP_KEYS; do
      probe_paged rules-by-profile "api/rules/search.protobuf?qprofile=$(urlenc "$QP_KEY")&organization=$ORG_E&activation=true&f=templateKey,actives&types=CODE_SMELL,BUG,VULNERABILITY,SECURITY_HOTSPOT&s=key" "application/x-protobuf"
    done
  else
    skip_row rules-by-profile "no quality profile key"
  fi

  probe tree-json "api/components/tree?qualifiers=FIL,UTS&component=$PROJECT_E&organization=$ORG_E&ps=1&p=1" "application/json" no
  FILE_KEY="$(json_get "$LAST_BODY" 'd["components"][0]["key"]')"
  warn_if_paged tree "$LAST_BODY"
  probe tree "api/components/tree.protobuf?qualifiers=FIL,UTS&component=$PROJECT_E&organization=$ORG_E&$PS" "application/x-protobuf" yes
  probe settings-project "api/settings/values.protobuf?component=$PROJECT_E" "application/x-protobuf" yes
  # Core's only caller (LocalStorageSynchronizer) sends no branch, so the recording does not ask for one.
  probe measures-new-code-periods "api/measures/component.protobuf?additionalFields=periods&metricKeys=projects&component=$PROJECT_E" "application/x-protobuf" yes
  probe measures-new-code-period "api/measures/component.protobuf?additionalFields=period&metricKeys=projects&component=$PROJECT_E" "application/x-protobuf" yes

  probe issues-json "api/issues/search?componentKeys=$PROJECT_E&organization=$ORG_E&ps=1&p=1&facets=impactSeverities,cleanCodeAttributeCategories" "application/json" no
  ISSUE_KEY="$(json_get "$LAST_BODY" 'd["issues"][0]["key"]')"
  # Core asks for taint vulnerabilities with the taint rules the server has. A server without taint rules gives core
  # an empty list, so the call goes out with an empty rules=. The recording matches any rules value.
  REC_ANY=rules REC_NOTE="Core sends rules= with the server's taint rule keys; with none on the server it is empty. This recording matches any rules value." \
    probe issues-vulns "api/issues/search.protobuf?statuses=OPEN,CONFIRMED,REOPENED,RESOLVED&types=VULNERABILITY&componentKeys=$PROJECT_E&rules=&branch=$BRANCH_E&organization=$ORG_E&$PS" "application/x-protobuf" yes
  if [ -n "$ISSUE_KEY" ]; then
    probe issue-by-key "api/issues/search.protobuf?issues=$(urlenc "$ISSUE_KEY")&additionalFields=transitions&organization=$ORG_E&ps=1&p=1" "application/x-protobuf" yes
    probe issue-by-key-branch "api/issues/search.protobuf?issues=$(urlenc "$ISSUE_KEY")&componentKeys=$PROJECT_E&branch=$BRANCH_E&ps=1&p=1" "application/x-protobuf" yes
  else
    skip_row issue-by-key "project has no issue"
  fi
  CHANGED_SINCE="${PROBE_PULL_CHANGED_SINCE:-}"
  PULL_EXTRA=""
  [ -n "$CHANGED_SINCE" ] && PULL_EXTRA="&changedSince=$CHANGED_SINCE"
  PULL_NOTE="SonarQube-type connections only, not called for CodeScan: core calls this only for a SonarQube-type connection, and the CodeScan plugin connects as a SonarCloud-type one. Recorded for completeness; the languages parameter is not matched."
  REC_IGNORE=languages REC_NOTE="$PULL_NOTE" probe issues-pull       "api/issues/pull?projectKey=$PROJECT_E&branchName=$BRANCH_E&languages=$ALL_LANGS$PULL_EXTRA" "application/x-protobuf" yes
  REC_IGNORE=languages REC_NOTE="$PULL_NOTE" probe issues-pull-taint "api/issues/pull_taint?projectKey=$PROJECT_E&branchName=$BRANCH_E&languages=java,js,ts,py,php$PULL_EXTRA" "application/x-protobuf" yes
  REC_IGNORE=languages REC_NOTE="$PULL_NOTE" probe hotspots-pull     "api/hotspots/pull?projectKey=$PROJECT_E&branchName=$BRANCH_E&languages=$ALL_LANGS$PULL_EXTRA" "application/x-protobuf" yes
  probe hotspots-search-json "api/hotspots/search?projectKey=$PROJECT_E&branch=$BRANCH_E&ps=1&p=1" "application/json" no
  HOTSPOT_KEY="$(json_get "$LAST_BODY" 'd["hotspots"][0]["key"]')"
  warn_if_paged hotspots-search "$LAST_BODY"
  REC_NOTE="Recorded without the files parameter. Core's per-file call adds &files=<path>; WireMock ignores extra query parameters, so that request gets this same whole-project answer, not one filtered by file. Tests must not rely on file filtering." \
    probe hotspots-search   "api/hotspots/search.protobuf?projectKey=$PROJECT_E&branch=$BRANCH_E&$PS" "application/x-protobuf" yes
  if [ -n "$HOTSPOT_KEY" ]; then
    probe hotspot-show    "api/hotspots/show.protobuf?hotspot=$(urlenc "$HOTSPOT_KEY")" "application/x-protobuf" yes
  else
    skip_row hotspot-show "project has no hotspot"
  fi
  if [ -n "$FILE_KEY" ]; then
    REC_IGNORE=branch REC_NOTE="Core asks with a branch, with a pull request, or with neither; the recording does not match on the branch." \
      probe sources-raw     "api/sources/raw?key=$(urlenc "$FILE_KEY")&branch=$BRANCH_E" "text/plain" yes
    # Core also fetches the source of every file with a taint vulnerability, and of the file of an issue or hotspot
    # opened from the browser. Only one file is fetched for real; a stand-in answers every other key. No request.
    if [ "$LAST_STATUS" -ge 200 ] 2>/dev/null && [ "$LAST_STATUS" -lt 300 ]; then
      python3 -I - "$RAW" "$(printf '%02da-sources-raw-fallback' "$N")" <<'PY'
import json, sys
raw, id_ = sys.argv[1:3]
with open(raw + "/" + id_ + ".body", "w") as f:
    f.write("// Stand-in source text for tests: not the real file.\n")
json.dump({"path": "api/sources/raw?key=any", "status": 200, "contentType": "text/plain", "bodyFile": id_ + ".body",
           "anyValueParams": ["key"], "priority": 10, "fallback": True,
           "note": "Stand-in for every file key that no other recording has (with or without a branch). Core asks "
                   "for the source of each file with a taint vulnerability and of the file of an issue or hotspot "
                   "opened from the browser; only one file is recorded for real. Snippets and line hashes core "
                   "builds from this text are not real."},
          open(raw + "/" + id_ + ".meta.json", "w"))
PY
    fi
  else
    skip_row sources-raw "no file key"
  fi
  REC_ANY=key REC_NOTE="Real server behaviour that core must handle: CodeScan answered HTTP {status} with content type {ctype}, not the protobuf issue list core expects. Core's IssueApi must treat this as no issues, not crash. Core asks with the project key, or with project:path for one file; the recording matches any key." \
    probe batch-issues      "batch/issues?key=$PROJECT_E&branch=$BRANCH_E" "application/x-protobuf" yes
  REC_ANY=from probe developers-events "api/developers/search_events?projects=$PROJECT_E&from=2024-01-01T00%3A00%3A00%2B0000" "application/json" yes
  # Server-sent events: if the stream opens it never ends, so a timeout after the status line is normal.
  REC_IGNORE=languages REC_NOTE="SonarQube-type connections only, not called for CodeScan: core opens this event stream only for a SonarQube-type connection (a SonarCloud-type one uses a WebSocket instead). Recorded for completeness: CodeScan answered HTTP {status}. The languages parameter is not matched." \
    probe push-events       "api/push/sonarlint_events?projectKeys=$PROJECT_E&languages=java,js" "text/event-stream" error-only --max-time 5
fi

# Error answers core has to cope with (all GET): an organisation and a project that do not exist.
probe unknown-org     "api/organizations/search.protobuf?organizations=no-such-org-for-tests&$PS" "application/x-protobuf" yes
probe unknown-project-show "api/components/show.protobuf?component=no-such-project-for-tests" "application/x-protobuf" yes
probe unknown-project-qprofiles "api/qualityprofiles/search.protobuf?project=no-such-project-for-tests&organization=$ORG_E" "application/x-protobuf" yes

# plugin download: ask for the first bytes only, and record a tiny stand-in jar for every plugin core would fetch
# (the ones the server marks sonarLintSupported). The real plugin files are big and are not stored.
PLUGIN_KEYS="$(json_get "$PLUGINS_BODY" '"\n".join([x["key"] for x in d["plugins"] if x.get("sonarLintSupported")][:int(__import__("os").environ.get("PROBE_MAX_PLUGINS", "20"))])')"
if [ -n "$PLUGIN_KEYS" ]; then
  for PLUGIN_KEY in $PLUGIN_KEYS; do
    REC_NOTE="Stand-in: a tiny jar made by the probe, not the real plugin. The real server answers with the full plugin jar (the probe asked for 100 bytes: HTTP {status}, content type {ctype}). Enough to prove the address and the plugin parameter; not enough to load the plugin." \
      probe plugin-download "api/plugins/download?plugin=$(urlenc "$PLUGIN_KEY")" "application/octet-stream" standin -r 0-99 --max-filesize 1000000
  done
else
  skip_row plugin-download "no plugin the server marks sonarLintSupported"
fi

# WebSocket: SonarCloud uses wss://events-api.sonarcloud.io/. Look for a CodeScan equivalent.
WS_HEADERS=(-H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")
probe ws-upgrade-root "" "*/*" no --http1.1 --max-time 5 "${WS_HEADERS[@]}"
probe ws-upgrade-same-host "api/push/sonarlint_events?projectKeys=${PROJECT_E:-none}&languages=java" "*/*" no --http1.1 --max-time 5 "${WS_HEADERS[@]}"
HOST_PART="${URL#*://}"; HOST_PART="${HOST_PART%%/*}"; SCHEME="${URL%%://*}"
PARENT_HOST="${HOST_PART#*.}"
for ws_host in "events-api.$HOST_PART" "events-api.$PARENT_HOST"; do
  N=$((N + 1))
  WS_ID="$(printf '%02d-ws-%s' "$N" "$ws_host")"
  # No credentials are sent to these guessed host names.
  ws_status="$(curl -sS --connect-timeout "$CT" --max-time 5 --http1.1 "${WS_HEADERS[@]}" -o "$RAW/$WS_ID.body" -w '%{http_code}' "$SCHEME://$ws_host/" 2>"$RAW/$WS_ID.err")"
  ws_rc=$?
  if [ "$ws_rc" -ne 0 ] && { [ -z "$ws_status" ] || [ "$ws_status" = "000" ]; }; then
    ws_status="ERR$ws_rc"; ws_note="$(head -c 70 "$RAW/$WS_ID.err" | tr '|\n' '  ')"
  else
    ws_note="$(head -c 70 "$RAW/$WS_ID.body" 2>/dev/null | tr '|\n`' '   ')"
  fi
  if [ "$ws_status" = "101" ]; then ws_supported="yes"; else ws_supported="no"; fi
  say "| $WS_ID | \`$SCHEME://$ws_host/\` (upgrade, no credentials) | $ws_status | - | \`$ws_note\` | $ws_supported |" | tee -a "$TABLE"
done

# Calls that change data are never made.
note_row "api/issues/do_transition (POST)" "write call"
note_row "api/issues/add_comment (POST)" "write call"
note_row "api/issues/anticipated_transitions (POST)" "write call"
note_row "api/hotspots/change_status (POST)" "write call"
note_row "api/user_tokens/revoke (POST)" "write call"
note_row "api/v2/*" "core 10.12.1 calls no api/v2 address"

echo
say "Done: $OK_COUNT answered 2xx, $NO_COUNT did not (of $N probes). Auth style: $AUTH_STYLE."
[ -n "$TABLE_FILE" ] && { say "$(cat "$TABLE")" > "$TABLE_FILE"; say "Table written to $TABLE_FILE"; }

if [ -n "$NO_ANSWER" ]; then
  say "probe-endpoints: no answer from call(s) that should be recorded:$NO_ANSWER (see the table)."
  [ -n "$RECORD_DIR" ] && say "probe-endpoints: the recordings in $RECORD_DIR are not replaced. Run the probe again."
  exit 4
fi

if [ -n "$RECORD_DIR" ]; then
  MAPPINGS="$WORK/mappings"
  SCRUBBED="$WORK/scrubbed"
  python3 -I "$HERE/build-mapping.py" "$RAW" "$MAPPINGS" || exit 1
  CODESCAN_TEST_ORG="$ORG" CODESCAN_TEST_PROJECT="$PROJECT" CODESCAN_TEST_TOKEN="$TOKEN" \
    CODESCAN_SCRUB_USERS="$SCRUB_USERS" CODESCAN_SCRUB_EXTRA="$SCRUB_EXTRA" CODESCAN_SCRUB_EXACT="$SCRUB_EXACT" \
    python3 -I "$HERE/scrub-recording.py" "$MAPPINGS" "$SCRUBBED" || exit 1
  # Only now (the scrub worked) replace the old numbered recordings, so none is left over from an earlier run.
  # Other files in the folder are not touched.
  mkdir -p "$RECORD_DIR"
  for f in "$RECORD_DIR"/*.json; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in [0-9]*-*.json) rm -f "$f" ;; esac
  done
  cp "$SCRUBBED"/*.json "$RECORD_DIR"/ || exit 1
fi
exit 0
