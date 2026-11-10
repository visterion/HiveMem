"""Tests for hivemem.py against a loopback fake HTTP server."""

import http.server
import json
import threading
import time
import unittest

import support  # noqa: F401  (sys.path bootstrap)

import hivemem


def envelope(payload):
    return {"jsonrpc": "2.0", "id": 1,
            "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}}


class _Server:
    """Loopback server recording requests and replying with a canned response."""

    def __init__(self):
        self.status = 200
        self.body = b"{}"
        self.delay = 0.0
        self.requests = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def _reply(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                outer.requests.append({"method": self.command, "path": self.path,
                                       "headers": dict(self.headers), "body": raw})
                if outer.delay:
                    time.sleep(outer.delay)
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(outer.body)))
                self.end_headers()
                try:
                    self.wfile.write(outer.body)
                except OSError:
                    pass

            do_GET = _reply
            do_POST = _reply

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def reply(self, obj, status=200):
        self.status = status
        self.body = obj if isinstance(obj, bytes) else json.dumps(obj).encode()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join()


class McpClientTest(unittest.TestCase):
    def setUp(self):
        self.server = _Server()
        self.addCleanup(self.server.close)
        self.client = hivemem.McpClient(self.server.url, "tok")

    def test_add_cell_success(self):
        self.server.reply(envelope({"inserted": True, "id": "c1"}))
        result = self.client.add_cell(content="x", realm="longmemeval", topic="dabc",
                                      signal="events", valid_from="2023-05-20T02:21:00Z")
        self.assertEqual(result, {"inserted": True, "id": "c1"})
        request = self.server.requests[0]
        self.assertEqual(request["path"], "/mcp")
        self.assertEqual(request["headers"]["Authorization"], "Bearer tok")
        self.assertEqual(request["headers"]["Content-Type"], "application/json")
        body = json.loads(request["body"])
        self.assertEqual(body["method"], "tools/call")
        self.assertEqual(body["params"], {"name": "add_cell", "arguments": {
            "content": "x", "realm": "longmemeval", "topic": "dabc", "signal": "events",
            "valid_from": "2023-05-20T02:21:00Z"}})

    def test_jsonrpc_error_raises(self):
        self.server.reply({"jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "boom-rpc"}})
        with self.assertRaisesRegex(hivemem.HiveMemError, "boom-rpc"):
            self.client.call("search", {})

    def test_is_error_raises(self):
        self.server.reply({"jsonrpc": "2.0", "id": 1, "result": {
            "isError": True, "content": [{"type": "text", "text": "boom-tool"}]}})
        with self.assertRaisesRegex(hivemem.HiveMemError, "boom-tool"):
            self.client.call("search", {})

    def test_not_inserted_raises(self):
        self.server.reply(envelope({"inserted": False, "reason": "duplicate-ish"}))
        with self.assertRaisesRegex(hivemem.HiveMemError, "duplicate-ish"):
            self.client.add_cell(content="x", realm="r", topic="t", signal="events",
                                 valid_from="2023-05-20T02:21:00Z")

    def test_non_json_body_raises(self):
        self.server.reply(b"<html>bad gateway</html>")
        with self.assertRaisesRegex(hivemem.HiveMemError, "bad gateway"):
            self.client.call("search", {})

    def test_http_500_raises(self):
        self.server.reply(b"server exploded", status=500)
        with self.assertRaisesRegex(hivemem.HiveMemError, "500"):
            self.client.call("search", {})

    def test_connection_refused(self):
        dead = _Server()
        url = dead.url
        dead.close()
        with self.assertRaises(hivemem.HiveMemError):
            hivemem.McpClient(url, "tok", timeout=2).call("search", {})

    def test_timeout_raises(self):
        self.server.delay = 1.0
        self.server.reply(envelope([]))
        with self.assertRaises(hivemem.HiveMemError):
            hivemem.McpClient(self.server.url, "tok", timeout=0.2).call("search", {})

    def test_search_returns_list(self):
        hits = [{"topic": "dabc", "score_total": 0.5}]
        self.server.reply(envelope(hits))
        self.assertEqual(self.client.search({"query": "q"}), hits)

    def test_search_non_list_raises(self):
        self.server.reply(envelope({"results": []}))
        with self.assertRaisesRegex(hivemem.HiveMemError, "list"):
            self.client.search({"query": "q"})


class SidecarTest(unittest.TestCase):
    def setUp(self):
        self.server = _Server()
        self.addCleanup(self.server.close)
        self.sidecar = hivemem.Sidecar(self.server.url)

    def test_info(self):
        self.server.reply({"model": "m", "max_chars": 50000})
        self.assertEqual(self.sidecar.info(), {"model": "m", "max_chars": 50000})
        self.assertEqual(self.server.requests[0]["method"], "GET")
        self.assertEqual(self.server.requests[0]["path"], "/info")

    def test_embed_query(self):
        self.server.reply({"vector": [0.1, 0.2]})
        self.assertEqual(self.sidecar.embed_query("q?"), [0.1, 0.2])
        request = self.server.requests[0]
        self.assertEqual(request["method"], "POST")
        self.assertEqual(request["path"], "/embeddings")
        self.assertEqual(json.loads(request["body"]), {"text": "q?", "mode": "query"})

    def test_embed_without_vector(self):
        self.server.reply({"error": "nope"})
        with self.assertRaises(hivemem.HiveMemError):
            self.sidecar.embed_query("q?")


if __name__ == "__main__":
    unittest.main()
