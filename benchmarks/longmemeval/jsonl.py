"""Append-only JSON-lines files with repair of a write cut off by a crash (stdlib only)."""

import json
import pathlib
import sys
from collections.abc import Mapping


class JsonlError(RuntimeError):
    """A JSON-lines file holds an invalid line that is not a crash tail."""


def _parse(line: str):
    try:
        value = json.loads(line)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def read(path: pathlib.Path) -> list[dict]:
    """Return the records of ``path`` (``[]`` when missing).

    A last line that is not valid JSON is a write cut off by a crash: it is
    dropped with a warning and removed from the file, so the next ``append``
    starts on a clean line. An invalid line anywhere else raises ``JsonlError``.
    """
    path = pathlib.Path(path)
    if not path.exists():
        return []
    lines = path.read_text(encoding="utf-8").split("\n")
    filled = [i for i, line in enumerate(lines) if line.strip()]
    last = filled[-1] if filled else -1
    records = []
    for index in filled:
        record = _parse(lines[index])
        if record is not None:
            records.append(record)
        elif index == last:
            print(f"warning: dropping cut-off last line {index + 1} of {path.name}", file=sys.stderr)
            kept = lines[:index]
            path.write_text("".join(line + "\n" for line in kept), encoding="utf-8")
        else:
            raise JsonlError(f"{path.name}: line {index + 1} is not a valid JSON object")
    return records


def append(path: pathlib.Path, record: Mapping) -> None:
    """Append ``record`` as one JSON line and flush it; creates parent directories."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record) + "\n"
    with open(path, "ab+") as handle:
        size = handle.seek(0, 2)
        if size:
            handle.seek(size - 1)
            if handle.read(1) != b"\n":
                handle.write(b"\n")
        handle.write(line.encode("utf-8"))
        handle.flush()
