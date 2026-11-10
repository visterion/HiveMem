"""HiveMem MCP client and embedding-sidecar client (standard library only).

Every transport or protocol problem is raised as ``HiveMemError`` whose message
names the tool (or endpoint) and carries up to 300 characters of the server's
text.
"""

import http.client
import json
import urllib.error
import urllib.request

_SNIPPET = 300


class HiveMemError(RuntimeError):
    """A HiveMem or sidecar call failed."""


def _snippet(text: object) -> str:
    return str(text)[:_SNIPPET]


def _http(url: str, data: bytes | None, headers: dict, timeout: float, label: str) -> str:
    """Send one request and return the body text, or raise HiveMemError."""
    request = urllib.request.Request(url, data=data, headers=headers,
                                     method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        try:
            text = error.read().decode("utf-8", errors="replace")
        except OSError:
            text = ""
        finally:
            error.close()
        raise HiveMemError(f"{label}: HTTP {error.code}: {_snippet(text)}") from error
    except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException) as error:
        raise HiveMemError(f"{label}: {_snippet(error)}") from error


def _parse_json(text: str, label: str) -> object:
    try:
        return json.loads(text)
    except (ValueError, TypeError) as error:
        raise HiveMemError(f"{label}: invalid JSON: {_snippet(text)}") from error


class McpClient:
    """JSON-RPC ``tools/call`` client for the HiveMem ``/mcp`` endpoint."""

    def __init__(self, base_url: str, token: str, timeout: float = 600.0) -> None:
        self._url = base_url.rstrip("/") + "/mcp"
        self._token = token
        self._timeout = timeout
        self._next_id = 0

    def call(self, name: str, arguments: dict) -> object:
        """Call a tool and return the parsed JSON of ``result.content[0].text``."""
        self._next_id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self._next_id, "method": "tools/call",
                           "params": {"name": name, "arguments": arguments}}).encode("utf-8")
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self._token}"}
        text = _http(self._url, body, headers, self._timeout, name)
        reply = _parse_json(text, name)
        if not isinstance(reply, dict):
            raise HiveMemError(f"{name}: unexpected reply: {_snippet(text)}")
        if "error" in reply:
            error = reply["error"]
            message = error.get("message", error) if isinstance(error, dict) else error
            raise HiveMemError(f"{name}: {_snippet(message)}")
        try:
            result = reply["result"]
            content_text = result["content"][0]["text"]
        except (KeyError, IndexError, TypeError) as error:
            raise HiveMemError(f"{name}: malformed result: {_snippet(text)}") from error
        if result.get("isError"):
            raise HiveMemError(f"{name}: {_snippet(content_text)}")
        return _parse_json(content_text, name)

    def add_cell(self, *, content: str, realm: str, topic: str, signal: str,
                 valid_from: str) -> dict:
        """Insert a cell; raise unless the server reports ``inserted`` true."""
        result = self.call("add_cell", {"content": content, "realm": realm, "topic": topic,
                                        "signal": signal, "valid_from": valid_from})
        if not isinstance(result, dict) or result.get("inserted") is not True:
            raise HiveMemError(f"add_cell: not inserted: {_snippet(json.dumps(result))}")
        return result

    def search(self, arguments: dict) -> list[dict]:
        """Run the ``search`` tool; the reply must be a JSON list of hits."""
        result = self.call("search", arguments)
        if not isinstance(result, list):
            raise HiveMemError(f"search: expected a list, got: {_snippet(json.dumps(result))}")
        return result


class Sidecar:
    """Client for the embedding sidecar."""

    def __init__(self, base_url: str, timeout: float = 120.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = timeout

    def info(self) -> dict:
        """GET ``/info``."""
        text = _http(self._base + "/info", None, {}, self._timeout, "sidecar /info")
        result = _parse_json(text, "sidecar /info")
        if not isinstance(result, dict):
            raise HiveMemError(f"sidecar /info: unexpected reply: {_snippet(text)}")
        return result

    def embed_query(self, text: str) -> list[float]:
        """POST ``/embeddings`` in query mode and return the vector."""
        body = json.dumps({"text": text, "mode": "query"}).encode("utf-8")
        raw = _http(self._base + "/embeddings", body, {"Content-Type": "application/json"},
                    self._timeout, "sidecar /embeddings")
        result = _parse_json(raw, "sidecar /embeddings")
        vector = result.get("vector") if isinstance(result, dict) else None
        if not isinstance(vector, list):
            raise HiveMemError(f"sidecar /embeddings: no vector: {_snippet(raw)}")
        return vector
