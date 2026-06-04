import json
from http.server import ThreadingHTTPServer

from app_onnx import Handler, INFO


if __name__ == "__main__":
    print("[bootstrap] /info =", json.dumps(INFO, indent=2), flush=True)
    print("Embedding service listening on port 80", flush=True)
    # ThreadingHTTPServer: the previous single-threaded HTTPServer served one request at a time,
    # so a burst (e.g. several sub-document embeds during one separation apply + the OCR backfill)
    # queued past the client's read timeout -> BrokenPipe server-side, truncated/octet-stream
    # response client-side, failing the ingest. onnxruntime Run() is thread-safe for concurrent
    # inference on a shared session, so handling requests concurrently is safe here.
    ThreadingHTTPServer(("0.0.0.0", 80), Handler).serve_forever()
