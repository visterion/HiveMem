"""Isolated, validated call of the local ``claude`` CLI (reader and judge models).

A call that failed, was truncated, or was answered by another model than the
one requested is never returned as an answer: it is retried and finally raised
as ``LlmError``. The module holds no mutable state and is safe to call from
several threads.
"""

import json
import shutil
import subprocess
import tempfile
import time
from typing import Callable, NamedTuple

SYSTEM_PROMPT = "You are a helpful assistant."
_DETAIL_LIMIT = 300


class LlmError(RuntimeError):
    """A model call that produced no valid reply."""


class Reply(NamedTuple):
    """A valid reply: the text plus the CLI's per-model and total usage."""

    text: str
    model_usage: dict
    usage: dict


def build_command(model: str, system_prompt: str = SYSTEM_PROMPT) -> list[str]:
    """Returns the isolated command line: no tools, no MCP, no settings, no session."""
    return ["claude", "-p", "--model", model, "--system-prompt", system_prompt,
            "--tools", "", "--strict-mcp-config", "--setting-sources", "",
            "--disable-slash-commands", "--no-session-persistence",
            "--output-format", "json"]


def _clip(value: object) -> str:
    return str(value).strip()[:_DETAIL_LIMIT]


def _parse(stdout: object) -> dict | None:
    """Returns stdout as a JSON object, or None when it is not one."""
    try:
        body = json.loads(stdout)
    except (TypeError, ValueError):
        return None
    return body if isinstance(body, dict) else None


def _detail(proc: subprocess.CompletedProcess) -> str:
    """Describes a process result: parsed JSON fields (else raw stdout), then stderr."""
    parts = []
    body = _parse(proc.stdout)
    if body is not None:
        fields = [f"{key}={_clip(body[key])!r}"
                  for key in ("is_error", "subtype", "stop_reason", "result") if key in body]
        parts.append(" ".join(fields))
    elif proc.stdout and str(proc.stdout).strip():
        parts.append(_clip(proc.stdout))
    if proc.stderr and str(proc.stderr).strip():
        parts.append(f"stderr: {_clip(proc.stderr)}")
    return " | ".join(parts)


def _check(proc: subprocess.CompletedProcess, model: str,
           validate: Callable[[str], bool] | None) -> Reply:
    """Returns the Reply of a valid process result; raises ValueError naming the reason otherwise."""
    if proc.returncode != 0:
        raise ValueError(f"exit code {proc.returncode}: {_detail(proc)}")
    body = _parse(proc.stdout)
    if body is None:
        raise ValueError(f"stdout is not a JSON object: {_detail(proc)}")
    if body.get("is_error") is not False:
        raise ValueError(f"is_error is {body.get('is_error')!r}: {_detail(proc)}")
    if body.get("stop_reason") != "end_turn":
        raise ValueError(f"stop_reason is {body.get('stop_reason')!r}: {_detail(proc)}")
    text = body.get("result")
    if not isinstance(text, str) or not text.strip():
        raise ValueError(f"empty result: {_detail(proc)}")
    model_usage = body.get("modelUsage")
    if not isinstance(model_usage, dict) or set(model_usage) != {model}:
        names = sorted(model_usage) if isinstance(model_usage, dict) else model_usage
        raise ValueError(f"modelUsage {names!r} is not exactly {model!r}; result: {_clip(text)!r}")
    if validate is not None and not validate(text):
        raise ValueError(f"validator rejected the reply: {_clip(text)!r}")
    usage = body.get("usage")
    return Reply(text, model_usage, usage if isinstance(usage, dict) else {})


def call(model: str, prompt: str, *, system_prompt: str = SYSTEM_PROMPT,
         validate: Callable[[str], bool] | None = None, retries: int = 3, pause: float = 5.0,
         timeout: float = 600.0, runner: Callable = subprocess.run,
         sleep: Callable = time.sleep) -> Reply:
    """Calls ``model`` with ``prompt`` on stdin; retries invalid replies, then raises LlmError."""
    command = build_command(model, system_prompt)
    reason = "no attempt made"
    for attempt in range(retries + 1):
        if attempt:
            sleep(pause * attempt)
        workdir = tempfile.mkdtemp(prefix="lme-llm-")
        try:
            proc = runner(command, input=prompt, capture_output=True, text=True,
                          timeout=timeout, cwd=workdir)
            return _check(proc, model, validate)
        except FileNotFoundError as exc:
            raise LlmError(f"claude CLI not found: {exc}") from exc
        except subprocess.TimeoutExpired:
            reason = f"timed out after {timeout} s"
        except ValueError as exc:
            reason = str(exc)
        except OSError as exc:
            reason = f"cannot run claude: {exc}"
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
    raise LlmError(f"{model}: no valid reply after {retries + 1} attempts; last: {reason}")


def cli_version(runner: Callable = subprocess.run) -> str:
    """Returns the stripped stdout of ``claude --version``."""
    try:
        proc = runner(["claude", "--version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise LlmError(f"cannot run claude --version: {exc}") from exc
    if proc.returncode != 0:
        raise LlmError(f"claude --version failed: {_detail(proc)}")
    return proc.stdout.strip()
