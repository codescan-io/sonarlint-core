"""The field types of the protobuf answers core 10.12.1 reads (proto-schema.json, made by build-proto-schema.py).

A field the schema declares as a nested message must parse as one at every level; a string field is text even if
its bytes happen to parse as a message.
"""
import json
import os

from protowire import read_varint, split_delimited, split_fields

_SCHEMA = None


def schema():
    global _SCHEMA
    if _SCHEMA is None:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "proto-schema.json"), encoding="utf-8") as f:
            _SCHEMA = json.load(f)
    return _SCHEMA


def root_type(path):
    return schema()["roots"].get(path)


def stream_types(path):
    """(type of the first message, type of every other message), or (None, None) if not known."""
    pair = schema()["streams"].get(path)
    return tuple(pair) if pair else (None, None)


def field_spec(type_name, number):
    """("string" | "bytes" | "message" | "scalar", nested type or None), or None if the schema does not say."""
    if not type_name:
        return None
    spec = schema()["messages"][type_name].get(str(number))
    return tuple(spec) if spec else None


def field_number(tag_bytes):
    return read_varint(tag_bytes, 0)[0] >> 3


def deep_problems(buf, type_name, where="body"):
    """Every place where a field declared as a nested message does not parse as one."""
    try:
        fields = split_fields(buf)
    except ValueError as e:
        return ["%s (%s) does not parse: %s" % (where, type_name, e)]
    problems = []
    for tag, wire, payload, _ in fields:
        if wire != 2:
            continue
        spec = field_spec(type_name, field_number(tag))
        if spec and spec[0] == "message":
            problems += deep_problems(payload, spec[1], "%s.%d" % (where, field_number(tag)))
    return problems


def body_problems(path, body):
    """Deep check of a recorded protobuf body by its address. Addresses the schema does not know are not checked."""
    first, rest = stream_types(path)
    if first:
        try:
            messages = split_delimited(body)
        except ValueError as e:
            return ["stream does not parse: %s" % e]
        out = []
        for i, m in enumerate(messages):
            out += deep_problems(m, first if i == 0 else rest, "message %d" % (i + 1))
        return out
    t = root_type(path)
    return deep_problems(body, t) if t else []
