"""psql channel: feeds SQL scripts to ``psql`` on standard input (stdlib only)."""

import os
import shlex
import subprocess
from collections.abc import Callable, Sequence


class DbError(RuntimeError):
    """A psql invocation failed or returned an unexpected shape."""


def default_command() -> list[str]:
    """Command that runs psql inside the benchmark stack's ``db`` service."""
    compose = os.path.join(os.path.dirname(os.path.abspath(__file__)), "docker-compose.yml")
    return ["docker", "compose", "-f", compose, "exec", "-T", "db", "psql", "-U", "hivemem",
            "-d", "hivemem", "-At", "-F", "\t", "-v", "ON_ERROR_STOP=1"]


def quote(value: str) -> str:
    """Return ``value`` as an SQL string literal (single quotes doubled)."""
    return "'" + value.replace("'", "''") + "'"


class Psql:
    """Runs SQL scripts through a psql command line."""

    def __init__(self, command: Sequence[str] | None = None,
                 runner: Callable = subprocess.run) -> None:
        self._command = list(command) if command is not None else default_command()
        self._runner = runner

    def run(self, script: str) -> str:
        """Feed ``script`` on standard input and return stdout."""
        shown = " ".join(shlex.quote(part) for part in self._command)
        try:
            result = self._runner(self._command, input=script, capture_output=True, text=True)
        except OSError as error:
            raise DbError(f"cannot run {shown}: {error}") from error
        if result.returncode != 0:
            raise DbError(f"{shown} exited with {result.returncode}: {(result.stderr or '').strip()}")
        return result.stdout

    def rows(self, script: str) -> list[list[str]]:
        """Run ``script``; return each non-empty stdout line split on tabs."""
        return [line.split("\t") for line in self.run(script).splitlines() if line]

    def scalar(self, script: str) -> str:
        """Run ``script``; return the only field of the only row."""
        rows = self.rows(script)
        if len(rows) != 1 or len(rows[0]) != 1:
            raise DbError(f"expected one row with one field, got {rows!r}")
        return rows[0][0]
