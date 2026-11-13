"""Small protobuf wire-format helpers shared by the recording tools (no schema needed).

Two shapes of body exist in CodeScan answers:
  * one message                      (for example api/rules/search.protobuf)
  * a stream of length-prefixed messages, each one "varint length + message bytes"
    (api/issues/pull, api/issues/pull_taint, api/hotspots/pull and batch/issues; core reads them with
    parseDelimitedFrom / readMessages)

All parsing here is strict: any byte that does not fit raises ValueError.
"""


def read_varint(buf, i):
    shift = value = 0
    while True:
        if i >= len(buf) or shift > 63:
            raise ValueError("bad varint")
        b = buf[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def write_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def split_fields(buf):
    """Strictly split a protobuf message into (tag_bytes, wire_type, payload, raw_bytes)."""
    i, fields = 0, []
    while i < len(buf):
        start = i
        tag, i = read_varint(buf, i)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("field number 0")
        tag_bytes = buf[start:i]
        if wire == 0:
            _, i = read_varint(buf, i)
            payload = None
        elif wire == 1:
            i += 8
            payload = None
        elif wire == 5:
            i += 4
            payload = None
        elif wire == 2:
            length, i = read_varint(buf, i)
            payload = buf[i:i + length]
            if len(payload) != length:
                raise ValueError("length runs past end")
            i += length
        else:
            raise ValueError("unsupported wire type %d" % wire)
        if i > len(buf):
            raise ValueError("field runs past end")
        fields.append((tag_bytes, wire, payload, buf[start:i]))
    return fields


def split_delimited(buf):
    """Split a stream of length-prefixed messages into a list of message byte strings.

    Raises ValueError if a length runs past the end, or if any message is not a valid protobuf message.
    """
    messages, i = [], 0
    while i < len(buf):
        length, i = read_varint(buf, i)
        msg = buf[i:i + length]
        if len(msg) != length:
            raise ValueError("message %d: length %d runs past the end" % (len(messages) + 1, length))
        try:
            split_fields(msg)
        except ValueError as e:
            raise ValueError("message %d is not a protobuf message: %s" % (len(messages) + 1, e))
        messages.append(msg)
        i += length
    return messages


def join_delimited(messages):
    return b"".join(write_varint(len(m)) + m for m in messages)


def is_plain_text(buf):
    """True if the bytes are readable text (so they must not be taken apart as a protobuf message)."""
    try:
        return buf.decode("utf-8").isprintable()
    except UnicodeDecodeError:
        return False


def iter_strings(buf, depth=0):
    """Yield every length-delimited payload in a message, also those inside nested messages."""
    if depth > 12:
        return
    try:
        fields = split_fields(buf)
    except ValueError:
        return
    for _, wire, payload, _ in fields:
        if wire == 2:
            yield payload
            if not is_plain_text(payload):
                yield from iter_strings(payload, depth + 1)
