import importlib.util
import os
import sys
import tempfile
import types
import unittest
from os import environ as _ENV
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("app_onnx.py")


def load_module():
    spec = importlib.util.spec_from_file_location("embedding_service_app_onnx", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def stub_modules():
    fake_numpy = types.SimpleNamespace(
        int64=int,
        float32=float,
        array=lambda value, dtype=None: value,
        zeros_like=lambda value: value,
        expand_dims=lambda value, axis=-1: value,
        sum=lambda value, axis=None: value,
        clip=lambda value, a_min=None, a_max=None: value,
        linalg=types.SimpleNamespace(norm=lambda value, axis=None, keepdims=None: value),
    )

    class DummyTokenizer:
        @staticmethod
        def from_file(path):
            return DummyTokenizer()

        def enable_padding(self, length):
            return None

        def enable_truncation(self, max_length):
            return None

    fake_tokenizers = types.SimpleNamespace(Tokenizer=DummyTokenizer)
    fake_onnxruntime = types.SimpleNamespace(InferenceSession=lambda path: object())
    return {
        "numpy": fake_numpy,
        "onnxruntime": fake_onnxruntime,
        "tokenizers": fake_tokenizers,
    }


class AppOnnxConfigTest(unittest.TestCase):
    def setUp(self):
        self.module_name = "embedding_service_app_onnx"
        self.env_patch = mock.patch.dict(_ENV, {"EMBEDDING_SKIP_BOOTSTRAP": "1"}, clear=False)
        self.env_patch.start()
        self.modules = mock.patch.dict(sys.modules, stub_modules())
        self.modules.start()
        sys.modules.pop(self.module_name, None)

    def tearDown(self):
        sys.modules.pop(self.module_name, None)
        self.modules.stop()
        self.env_patch.stop()

    def test_resolve_model_dir_prefers_manual_path(self):
        with tempfile.TemporaryDirectory() as model_dir:
            with mock.patch.dict(
                _ENV,
                {"EMBEDDING_SKIP_BOOTSTRAP": "1", "MODEL_PATH": model_dir},
                clear=True,
            ):
                module = load_module()
            resolved, source = module.resolve_model_dir()
            self.assertEqual((resolved, source), (model_dir, "manual"))

    def test_find_onnx_uses_explicit_override(self):
        with tempfile.TemporaryDirectory() as model_dir:
            Path(model_dir, "custom.onnx").write_text("x")
            with mock.patch.dict(
                _ENV,
                {"EMBEDDING_SKIP_BOOTSTRAP": "1", "ONNX_FILE": "custom.onnx"},
                clear=True,
            ):
                module = load_module()
            self.assertEqual(module.find_onnx(model_dir), os.path.join(model_dir, "custom.onnx"))

    def test_find_tokenizer_falls_back_to_nested_file(self):
        with tempfile.TemporaryDirectory() as model_dir:
            nested = Path(model_dir, "onnx")
            nested.mkdir()
            Path(nested, "tokenizer.json").write_text("{}")
            module = load_module()
            self.assertEqual(module.find_tokenizer(model_dir), str(nested / "tokenizer.json"))

    def test_build_info_reports_repo_only_for_cached_models(self):
        with mock.patch.dict(
            _ENV,
            {
                "EMBEDDING_SKIP_BOOTSTRAP": "1",
                "MODEL_REPO": "acme/model",
                "POOLING": "cls",
                "MAX_LENGTH": "512",
                "QUERY_PREFIX": "Q: ",
                "DOCUMENT_PREFIX": "D: ",
            },
            clear=True,
        ):
            module = load_module()
        module.INPUT_NAMES = {"attention_mask", "input_ids"}
        info = module.build_info(
            "demo-model",
            1024,
            "hf_cache",
            "/tmp/model",
            "/tmp/model/onnx/model.onnx",
            "/tmp/model/tokenizer.json",
        )
        self.assertEqual(info["repo"], "acme/model")
        self.assertEqual(info["pooling"], "cls")
        self.assertEqual(info["max_length"], 512)
        self.assertEqual(info["query_prefix"], "Q: ")
        self.assertEqual(info["document_prefix"], "D: ")
        self.assertEqual(info["inputs"], ["attention_mask", "input_ids"])


if __name__ == "__main__":
    unittest.main()
