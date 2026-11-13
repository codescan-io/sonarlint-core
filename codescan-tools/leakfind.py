"""Find real values in a recording file, in every form they can be written in.

Shared by scan-recordings.py (the check on committed recordings) and scrub-recording.py (its final check).

A file is read in several ways ("views"), and a value is looked for in each:
  * the file text as it is, with JSON \\u escapes undone, with URL encoding undone (%20 and +), with HTML
    entities undone
  * every JSON string and key, also inside JSON text held in a string (for example a response "body")
  * response.base64Body decoded, and every protobuf field inside it at every level
  * every run of base64 letters, decoded (at each of the 4 possible starting points)
  * for values of 6 or more characters: the base64 forms of the value itself, at each of the 3 byte alignments,
    so a value inside a longer base64 text is found too
Values of 4 or more characters are matched without regard to letter case (Unicode NFC, casefold). Shorter values
(short logins) are matched only as a whole value: a whole JSON string, a whole query parameter value, a whole
protobuf field, or a value in double quotes in the text.
"""
import base64
import html
import json
import re
import unicodedata
import urllib.parse

from protowire import split_delimited, split_fields

MIN_ANYWHERE = 4
MIN_IN_DECODED_BASE64 = 6   # shorter values would match random bytes of decoded base64 too often
MIN_NEEDLE = 8
B64_RUN = re.compile(r"[A-Za-z0-9+/_-]{8,}={0,2}")
U_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
MAX_DEPTH = 12


def norm(text):
    return unicodedata.normalize("NFC", text).casefold()


def _undo_u_escapes(text):
    return U_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), text)


def text_like(buf):
    """True if the bytes read as text: UTF-8 with only printable characters, tabs and line breaks."""
    try:
        s = buf.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return all(c.isprintable() or c in "\n\r\t" for c in s)


def _payloads(buf, depth=0):
    """Every length-delimited field at every level, read as one message and as a stream of messages."""
    if depth > MAX_DEPTH or not buf:
        return
    seen = []
    try:
        seen += [p for _, w, p, _ in split_fields(buf) if w == 2]
    except ValueError:
        pass
    if depth == 0:
        try:
            for m in split_delimited(buf):
                seen.append(m)
        except ValueError:
            pass
    for p in seen:
        yield p
        yield from _payloads(p, depth + 1)


class Views:
    """The ways one file can be read. Fill with add_file(); read the lists."""

    def __init__(self):
        self.plain = []      # whole texts: the file, the decoded body, with encodings undone
        self.strings = []    # JSON strings and keys, protobuf text fields
        self.decoded = []
        self.wholes = set()
        self.errors = []
        self._b64_sources = []

    def _add_text(self, text, to):
        to.append(text)
        u = _undo_u_escapes(text)
        for t in {u, urllib.parse.unquote(u), urllib.parse.unquote_plus(u), html.unescape(u)}:
            if t != text:
                to.append(t)

    def add_file(self, raw, is_json):
        text = raw.decode("utf-8", "replace")
        self._add_text(text, self.plain)
        for q in re.findall(r'\\*"([^"\\]{1,40})\\*"', text):
            self.wholes.add(norm(q))
        doc = None
        if is_json:
            try:
                doc = json.loads(raw)
            except ValueError as e:
                self.errors.append("not valid JSON: %s" % e)
        if doc is not None:
            self._walk(doc, None, 0)
        else:
            self._b64_sources.append(text)
        self._decode_runs()
        return self

    def _walk(self, obj, key, depth):
        if depth > MAX_DEPTH:
            return
        if isinstance(obj, dict):
            for k, v in obj.items():
                self._string(k, None, depth)
                self._walk(v, k, depth + 1)
        elif isinstance(obj, list):
            for v in obj:
                self._walk(v, key, depth + 1)
        elif isinstance(obj, str):
            if key == "base64Body":
                try:
                    self.add_body(base64.b64decode(obj))
                except ValueError as e:
                    self.errors.append("base64Body is not base64: %s" % e)
            else:
                self._string(obj, key, depth)

    def _string(self, s, key, depth):
        self._add_text(s, self.strings)
        self.wholes.add(norm(s))
        self._b64_sources.append(s)
        if any(c in s for c in "?&="):
            for part in (s, urllib.parse.unquote(s)):
                for k, v in urllib.parse.parse_qsl(part.split("?", 1)[-1], keep_blank_values=True):
                    self.wholes.update((norm(k), norm(v)))
        stripped = s.strip()
        if stripped[:1] in ("{", "[") and depth < MAX_DEPTH:
            try:
                self._walk(json.loads(stripped), key, depth + 1)
            except ValueError:
                pass

    def add_body(self, body):
        self.plain.append(body.decode("utf-8", "replace"))
        for p in _payloads(body):
            t = p.decode("utf-8", "replace")
            self.wholes.add(norm(t))
            if text_like(p):
                self._add_text(t, self.strings)
                self._b64_sources.append(t)

    def _decode_runs(self):
        for src in self._b64_sources:
            for run in B64_RUN.findall(src):
                for off in range(4):
                    s = run[off:].rstrip("=").replace("-", "+").replace("_", "/")
                    s = s[:len(s) - len(s) % 4]
                    if len(s) < 8:
                        continue
                    try:
                        self.decoded.append(base64.b64decode(s, validate=True).decode("utf-8", "replace"))
                    except ValueError:
                        pass
        self._b64_sources = []


def _needles(value):
    """Base64 pieces of the value at the 3 byte alignments (standard and URL-safe letters)."""
    vb = value.encode("utf-8")
    out = set()
    for k in range(3):
        e = base64.b64encode(b"\0" * k + vb).decode()
        end = len(e) - (4 if (k + len(vb)) % 3 else 0)
        piece = e[4 if k else 0:end]
        if len(piece) >= MIN_NEEDLE:
            out.add(piece)
            out.add(piece.replace("+", "-").replace("/", "_"))
    return out


class Finder:
    def __init__(self, values):
        values = [v for v in dict.fromkeys(values) if v]
        self.long = [(v, norm(v)) for v in values if len(v) >= MIN_ANYWHERE]
        self.short = [(v, norm(v)) for v in values if len(v) < MIN_ANYWHERE]
        self.needles = [(v, _needles(v)) for v, _ in self.long if len(v) >= MIN_IN_DECODED_BASE64]

    def find(self, views):
        """Problems found in one file's views. Each says the form and the value's length, never the value."""
        problems = []
        plain = "\x00".join(views.plain)
        strings = "\x00".join(views.strings)
        decoded = norm("\x00".join(views.decoded))
        normalized = norm(plain + "\x00" + strings)
        for value, n in self.long:
            if n in normalized:
                problems.append("holds a hidden value (%d chars)" % len(value))
            elif len(value) >= MIN_IN_DECODED_BASE64 and n in decoded:
                problems.append("holds a hidden value in base64 form (%d chars)" % len(value))
        for value, needles in self.needles:
            if any(nd in plain or nd in strings for nd in needles) and \
                    not any(p.endswith("(%d chars)" % len(value)) for p in problems):
                problems.append("holds a hidden value in base64 form, inside other base64 text (%d chars)" % len(value))
        for value, n in self.short:
            if n in views.wholes:
                problems.append("holds a short hidden value as a whole value (%d chars)" % len(value))
        return problems
