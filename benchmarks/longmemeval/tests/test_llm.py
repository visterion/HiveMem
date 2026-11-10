"""Tests for llm.py: the isolated, validated call of the local claude CLI."""

import json
import os
import subprocess
import unittest

import support  # noqa: F401

import llm


def reply(**override):
    body = {"is_error": False, "stop_reason": "end_turn", "result": "yes",
            "modelUsage": {"claude-sonnet-5-5": {"inputTokens": 2}}, "usage": {"input_tokens": 2}}
    body.update(override)
    return subprocess.CompletedProcess([], 0, json.dumps(body), "")


class Recorder:
    """Runner fake: returns or raises the queued items, repeating the last one."""

    def __init__(self, *items):
        self.items, self.calls = list(items), []

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        item = self.items[min(len(self.calls), len(self.items)) - 1]
        if isinstance(item, Exception):
            raise item
        return item


class LlmTest(unittest.TestCase):
    def call(self, runner, **kw):
        return llm.call("claude-sonnet-5-5", "PROMPT", runner=runner, sleep=lambda s: None, **kw)

    def test_command_is_the_isolated_form_and_prompt_is_on_stdin(self):
        runner = Recorder(reply())
        result = self.call(runner)
        command, kwargs = runner.calls[0]
        self.assertEqual(command, ["claude", "-p", "--model", "claude-sonnet-5-5", "--system-prompt",
                                   "You are a helpful assistant.", "--tools", "", "--strict-mcp-config",
                                   "--setting-sources", "", "--disable-slash-commands", "--no-session-persistence",
                                   "--output-format", "json"])
        self.assertEqual(kwargs["input"], "PROMPT")
        self.assertNotIn("PROMPT", command)
        self.assertEqual(result.text, "yes")
        self.assertEqual(result.model_usage, {"claude-sonnet-5-5": {"inputTokens": 2}})
        self.assertEqual(result.usage, {"input_tokens": 2})

    def test_runs_in_a_fresh_empty_directory(self):
        seen = []

        def runner(command, **kwargs):
            seen.append((kwargs["cwd"], os.listdir(kwargs["cwd"])))
            return reply()

        self.call(runner)
        self.call(runner)
        self.assertEqual([listing for _, listing in seen], [[], []])
        self.assertNotEqual(seen[0][0], seen[1][0])
        self.assertFalse(os.path.exists(seen[0][0]))

    def test_invalid_replies_are_retried_three_times_then_raise(self):
        invalid = [reply(is_error=True), subprocess.CompletedProcess([], 1, "", "Not logged in"),
                   subprocess.CompletedProcess([], 0, "not json", ""), reply(result=""), reply(result="   "),
                   reply(stop_reason="max_tokens"), reply(modelUsage={"claude-haiku-5-5": {}}),
                   reply(modelUsage={"claude-sonnet-5-5": {}, "claude-haiku-5-5": {}}),
                   subprocess.TimeoutExpired("claude", 600)]
        for item in invalid:
            with self.subTest(item=item):
                runner = Recorder(item)
                with self.assertRaises(llm.LlmError):
                    self.call(runner)
                self.assertEqual(len(runner.calls), 4)

    def test_recovers_when_a_retry_succeeds(self):
        runner = Recorder(reply(is_error=True), reply(result="no"))
        self.assertEqual(self.call(runner).text, "no")
        self.assertEqual(len(runner.calls), 2)

    def test_validator_rejection_counts_as_invalid(self):
        runner = Recorder(reply(result="Maybe, the user ..."))
        with self.assertRaises(llm.LlmError):
            self.call(runner, validate=lambda text: text.lower().startswith(("yes", "no")))
        self.assertEqual(len(runner.calls), 4)

    def test_not_logged_in_message_reaches_the_error(self):
        with self.assertRaisesRegex(llm.LlmError, "Not logged in"):
            self.call(Recorder(subprocess.CompletedProcess([], 1, "", "Not logged in")))

    def test_missing_binary_is_not_retried(self):
        runner = Recorder(FileNotFoundError("claude"))
        with self.assertRaisesRegex(llm.LlmError, "claude"):
            self.call(runner)
        self.assertEqual(len(runner.calls), 1)

    def test_sleeps_with_growing_pause_between_attempts(self):
        pauses = []
        runner = Recorder(reply(is_error=True))
        with self.assertRaises(llm.LlmError):
            llm.call("claude-sonnet-5-5", "P", runner=runner, sleep=pauses.append, pause=5.0)
        self.assertEqual(pauses, [5.0, 10.0, 15.0])

    def test_cli_version_is_stripped_stdout(self):
        runner = Recorder(subprocess.CompletedProcess([], 0, "9.9.9 (Claude Code)\n", ""))
        self.assertEqual(llm.cli_version(runner), "9.9.9 (Claude Code)")
        self.assertEqual(runner.calls[0][0], ["claude", "--version"])


if __name__ == "__main__":
    unittest.main()
