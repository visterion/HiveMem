"""Tests for db.py (psql channel); no real psql or docker is started."""

import os
import unittest

import support  # noqa: F401  (sys.path bootstrap)

import db


class PsqlTest(unittest.TestCase):
    def test_script_goes_to_stdin_of_the_command(self):
        runner = support.FakeRunner(lambda script: "a\tb\nc\td\n")
        psql = db.Psql(["psql", "-At"], runner=runner)
        self.assertEqual(psql.rows("SELECT 1"), [["a", "b"], ["c", "d"]])
        self.assertEqual(runner.calls, [(["psql", "-At"], "SELECT 1")])

    def test_scalar(self):
        self.assertEqual(
            db.Psql(["psql"], runner=support.FakeRunner(lambda s: "42\n")).scalar("SELECT 42"), "42")
        with self.assertRaises(db.DbError):
            db.Psql(["psql"], runner=support.FakeRunner(lambda s: "1\n2\n")).scalar("SELECT")

    def test_nonzero_exit_raises_with_stderr_and_command(self):
        runner = support.FakeRunner(lambda s: (1, "", 'service "db" is not running'))
        with self.assertRaisesRegex(db.DbError, 'service "db" is not running') as caught:
            db.Psql(["docker", "compose", "exec"], runner=runner).run("SELECT 1")
        self.assertIn("docker compose exec", str(caught.exception))

    def test_missing_binary_raises_db_error(self):
        runner = support.FakeRunner(lambda s: FileNotFoundError("docker"))
        with self.assertRaisesRegex(db.DbError, "docker"):
            db.Psql(["docker"], runner=runner).run("SELECT 1")

    def test_default_command(self):
        command = db.default_command()
        self.assertEqual(command[:3], ["docker", "compose", "-f"])
        self.assertTrue(os.path.isabs(command[3])
                        and command[3].endswith("benchmarks/longmemeval/docker-compose.yml"))
        self.assertEqual(command[4:], ["exec", "-T", "db", "psql", "-U", "hivemem", "-d", "hivemem",
                                       "-At", "-F", "\t", "-v", "ON_ERROR_STOP=1"])

    def test_quote(self):
        self.assertEqual(db.quote("a'b"), "'a''b'")


if __name__ == "__main__":
    unittest.main()
