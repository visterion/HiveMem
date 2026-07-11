import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

# --- Configuration ---------------------------------------------------------
# Resolution priority for the model directory:
#   1. MODEL_PATH -> use as-is (manually placed files, no HF contact)
#   2. MODEL_REPO -> snapshot_download into MODEL_CACHE/<slug>
MODEL_PATH = os.environ.get("MODEL_PATH", "").strip() or None
MODEL_REPO = os.environ.get(
    "MODEL_REPO",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
MODEL_NAME = os.environ.get("MODEL_NAME", "").strip() or None

# Optional explicit file names within the model directory
ONNX_FILE = os.environ.get("ONNX_FILE", "").strip() or None
TOKENIZER_FILE = os.environ.get("TOKENIZER_FILE", "").strip() or None

QUERY_PREFIX = os.environ.get("QUERY_PREFIX", "")
DOCUMENT_PREFIX = os.environ.get("DOCUMENT_PREFIX", "")
POOLING = os.environ.get("POOLING", "mean").lower()
MAX_LENGTH = int(os.environ.get("MAX_LENGTH", "128"))
CACHE_DIR = os.environ.get("MODEL_CACHE", "/app/models")
SKIP_BOOTSTRAP = os.environ.get("EMBEDDING_SKIP_BOOTSTRAP") == "1"

# File auto-detection order inside the model directory
ONNX_CANDIDATES = [
    "model_quantized.onnx",
    "model.onnx",
    "onnx/model_quantized.onnx",
    "onnx/model.onnx",
    "onnx/model_fp16.onnx",
]
TOKENIZER_CANDIDATES = ["tokenizer.json", "onnx/tokenizer.json"]

# Patterns pulled from HF when auto-downloading. Many repos ship dozens of ONNX
# variants; fetch only the minimum set needed for inference by default.
_DEFAULT_HF_PATTERNS = [
    "model_quantized.onnx",
    "model.onnx",
    "onnx/model_quantized.onnx",
    "onnx/model.onnx",
    "model_quantized.onnx_data",
    "model.onnx_data",
    "onnx/model_quantized.onnx_data",
    "onnx/model.onnx_data",
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "config.json",
    "sentencepiece.bpe.model",
    "vocab.txt",
]
_patterns_env = os.environ.get("HF_DOWNLOAD_PATTERNS", "").strip()
HF_ALLOW_PATTERNS = (
    [p.strip() for p in _patterns_env.split(",") if p.strip()]
    if _patterns_env
    else _DEFAULT_HF_PATTERNS
)

tokenizer = None
session = None
INPUT_NAMES = set()
MODEL_DIMENSION = 0
INFO = {"model": MODEL_NAME or "", "dimension": 0}


def download_from_hf(repo, dest):
    print(f"[bootstrap] snapshot_download {repo} -> {dest}", flush=True)
    os.makedirs(dest, exist_ok=True)
    from huggingface_hub import snapshot_download

    snapshot_download(
        repo_id=repo,
        local_dir=dest,
        allow_patterns=HF_ALLOW_PATTERNS,
    )
    open(os.path.join(dest, ".ready"), "w").close()
    print("[bootstrap] Download complete", flush=True)


def resolve_model_dir():
    if MODEL_PATH:
        if not os.path.isdir(MODEL_PATH):
            raise FileNotFoundError(f"MODEL_PATH does not exist: {MODEL_PATH}")
        print(f"[bootstrap] Using manual MODEL_PATH={MODEL_PATH}", flush=True)
        return MODEL_PATH, "manual"

    slug = MODEL_REPO.replace("/", "__")
    dest = os.path.join(CACHE_DIR, slug)
    if os.path.exists(os.path.join(dest, ".ready")):
        print(f"[bootstrap] Cached model at {dest}", flush=True)
    else:
        download_from_hf(MODEL_REPO, dest)
    return dest, "hf_cache"


def find_onnx(model_dir):
    if ONNX_FILE:
        path = os.path.join(model_dir, ONNX_FILE)
        if not os.path.exists(path):
            raise FileNotFoundError(f"ONNX_FILE not found: {path}")
        return path
    for candidate in ONNX_CANDIDATES:
        path = os.path.join(model_dir, candidate)
        if os.path.exists(path):
            return path
    for root, _, files in os.walk(model_dir):
        for filename in sorted(files):
            if filename.endswith(".onnx"):
                return os.path.join(root, filename)
    raise FileNotFoundError(f"No .onnx file found in {model_dir}")


def find_tokenizer(model_dir):
    if TOKENIZER_FILE:
        path = os.path.join(model_dir, TOKENIZER_FILE)
        if not os.path.exists(path):
            raise FileNotFoundError(f"TOKENIZER_FILE not found: {path}")
        return path
    for candidate in TOKENIZER_CANDIDATES:
        path = os.path.join(model_dir, candidate)
        if os.path.exists(path):
            return path
    for root, _, files in os.walk(model_dir):
        if "tokenizer.json" in files:
            return os.path.join(root, "tokenizer.json")
    raise FileNotFoundError(f"No tokenizer.json found in {model_dir}")


def mean_pooling(token_embeddings, attention_mask):
    mask_expanded = np.expand_dims(attention_mask, axis=-1)
    summed = np.sum(token_embeddings * mask_expanded, axis=1)
    counts = np.clip(np.sum(mask_expanded, axis=1), a_min=1e-9, a_max=None)
    return summed / counts


def cls_pooling(token_embeddings):
    return token_embeddings[:, 0, :]


def embed(text, mode="document"):
    if tokenizer is None or session is None:
        raise RuntimeError("Embedding runtime not initialized")

    if mode == "query" and QUERY_PREFIX:
        text = QUERY_PREFIX + text
    elif mode == "document" and DOCUMENT_PREFIX:
        text = DOCUMENT_PREFIX + text

    encoded = tokenizer.encode(text)
    input_ids = np.array([encoded.ids], dtype=np.int64)
    attention_mask = np.array([encoded.attention_mask], dtype=np.int64)
    inputs = {"input_ids": input_ids, "attention_mask": attention_mask}
    if "token_type_ids" in INPUT_NAMES:
        inputs["token_type_ids"] = np.zeros_like(input_ids)

    outputs = session.run(None, inputs)
    if POOLING == "cls":
        embedding = cls_pooling(outputs[0])
    else:
        embedding = mean_pooling(outputs[0], attention_mask.astype(np.float32))

    norm = np.linalg.norm(embedding, axis=1, keepdims=True)
    return (embedding / np.clip(norm, a_min=1e-9, a_max=None))[0].tolist()


def build_info(model_name, dimension, source, model_dir, onnx_path, tokenizer_path):
    info = {
        "model": model_name,
        "dimension": dimension,
        "source": source,
        "model_path": model_dir,
        "onnx_file": os.path.relpath(onnx_path, model_dir),
        "tokenizer_file": os.path.relpath(tokenizer_path, model_dir),
        "pooling": POOLING,
        "max_length": MAX_LENGTH,
        "query_prefix": QUERY_PREFIX,
        "document_prefix": DOCUMENT_PREFIX,
        "inputs": sorted(INPUT_NAMES),
    }
    if source == "hf_cache":
        info["repo"] = MODEL_REPO
    return info


def bootstrap_runtime():
    global tokenizer, session, INPUT_NAMES, MODEL_NAME, MODEL_DIMENSION, INFO

    model_dir, source = resolve_model_dir()
    onnx_path = find_onnx(model_dir)
    tokenizer_path = find_tokenizer(model_dir)

    if MODEL_NAME is None:
        MODEL_NAME = (
            os.path.basename(MODEL_PATH.rstrip("/"))
            if MODEL_PATH
            else MODEL_REPO.split("/")[-1]
        )

    tokenizer = Tokenizer.from_file(tokenizer_path)
    # No padding: inputs are embedded one at a time, so padding every request to
    # MAX_LENGTH would make short texts pay full-length inference cost.
    # Truncation still bounds the sequence length.
    tokenizer.no_padding()
    tokenizer.enable_truncation(max_length=MAX_LENGTH)

    session = ort.InferenceSession(onnx_path)
    INPUT_NAMES = {inp.name for inp in session.get_inputs()}
    MODEL_DIMENSION = len(embed("test"))
    INFO = build_info(MODEL_NAME, MODEL_DIMENSION, source, model_dir, onnx_path, tokenizer_path)
    return INFO


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # Bound how long a keep-alive connection idles waiting for the next request; without this
    # a stalled/abandoned client connection ties up a handler thread forever.
    timeout = 30

    def do_POST(self):
        if self.path != "/embeddings":
            self._respond(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeDecodeError):
            self._respond(400, {"error": "request body must be valid JSON"})
            return
        text = body.get("text") if isinstance(body, dict) else None
        if not isinstance(text, str):
            self._respond(400, {"error": "field 'text' is required and must be a string"})
            return
        mode = body.get("mode", "document")
        try:
            vector = embed(text, mode=mode)
        except Exception as exc:  # keep the server alive; report the failure as HTTP 500
            self._respond(500, {"error": f"embedding failed: {exc}"})
            return
        self._respond(200, {"vector": vector, "model": MODEL_NAME, "dimension": MODEL_DIMENSION})

    def do_GET(self):
        if self.path == "/info":
            self._respond(200, INFO)
        elif self.path == "/health":
            self._respond(200, {"status": "ok", "model": MODEL_NAME, "dimensions": MODEL_DIMENSION})
        else:
            self._respond(404, {"error": "not found"})

    def _respond(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


if not SKIP_BOOTSTRAP:
    bootstrap_runtime()


if __name__ == "__main__":
    print("[bootstrap] /info =", json.dumps(INFO, indent=2), flush=True)
    print("Embedding service listening on port 80", flush=True)
    HTTPServer(("0.0.0.0", 80), Handler).serve_forever()
