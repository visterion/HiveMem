import json
import os
import threading
from http.server import ThreadingHTTPServer

from app_onnx import Handler, INFO

# Hard cap on concurrent handler threads. With HTTP/1.1 keep-alive a thread stays
# bound to its connection, so the cap must comfortably exceed the Java client's
# connection-pool size — it exists to bound thread/memory blowup, not to serialize.
MAX_CONCURRENT_REQUESTS = int(os.environ.get("MAX_CONCURRENT_REQUESTS", "32"))


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """ThreadingHTTPServer with a bounded number of concurrent handler threads."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._request_slots = threading.BoundedSemaphore(MAX_CONCURRENT_REQUESTS)

    def process_request(self, request, client_address):
        self._request_slots.acquire()
        try:
            super().process_request(request, client_address)
        except Exception:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


if __name__ == "__main__":
    print("[bootstrap] /info =", json.dumps(INFO, indent=2), flush=True)
    print("Embedding service listening on port 80", flush=True)
    # Threading server: the previous single-threaded HTTPServer served one request at a time,
    # so a burst (e.g. several sub-document embeds during one separation apply + the OCR backfill)
    # queued past the client's read timeout -> BrokenPipe server-side, truncated/octet-stream
    # response client-side, failing the ingest. onnxruntime Run() is thread-safe for concurrent
    # inference on a shared session, so handling requests concurrently is safe here.
    BoundedThreadingHTTPServer(("0.0.0.0", 80), Handler).serve_forever()
