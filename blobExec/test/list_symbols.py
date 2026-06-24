#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile

WANTED_KINDS = {"function", "variable"}


def run_ctags(path):
    result = subprocess.run(
        ["ctags", "--output-format=json", "--fields=+neS", "-o", "-", path],
        capture_output=True, text=True, check=True,
    )
    tags = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        tag = json.loads(line)
        if tag.get("_type") != "tag":
            continue
        if tag.get("kind") not in WANTED_KINDS:
            continue
        tags.append(tag)

    tags.sort(key=lambda t: t.get("line", 0))
    for t in tags:
        typeref = t.get("typeref", "")
        _, _, type_name = typeref.partition(":")
        print(f"{t['kind']};{t['name']};{type_name or typeref};{t.get('signature', '')}")


def main():
    blob = sys.stdin.buffer.read()
    filename = os.environ.get("BFG_FILENAME", "")
    _, ext = os.path.splitext(filename)

    with tempfile.NamedTemporaryFile(suffix=ext, delete=False) as tf:
        tf.write(blob)
        tmp_path = tf.name
    try:
        run_ctags(tmp_path)
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    main()
