"""Tests for dataset.py."""

import hashlib
import json
import os
import pathlib
import tempfile
import unittest

import support  # noqa: F401  (must come first: sets up sys.path)
import dataset


class DatasetTest(unittest.TestCase):
    def test_twins_merge_and_gold_follows(self):
        corpus = support.fixture_corpus()
        self.assertEqual(len(corpus.documents), 6)
        self.assertEqual(support.key_of("noise_twin_9"), support.key_of("answer_tea_1"))
        twin = corpus.documents[support.key_of("answer_tea_1")]
        self.assertEqual(twin.session_ids, ("answer_tea_1", "noise_twin_9"))
        by_id = {q.id: q for q in corpus.questions}
        self.assertEqual(len(by_id["q_tea"].gold), 2)
        self.assertIn(twin.key, by_id["q_count"].haystack)

    def test_key_format_and_rendering(self):
        doc = support.fixture_corpus().documents[support.key_of("noise_bike_3")]
        self.assertRegex(doc.key, r"^d[0-9a-f]{16}$")
        self.assertEqual(doc.text, "\n".join(f"{role}: {content}" for role, content in doc.turns))
        self.assertEqual(doc.key, "d" + hashlib.sha256(doc.text.encode("utf-8")).hexdigest()[:16])

    def test_document_date_is_earliest_over_whole_dataset(self):
        corpus = support.fixture_corpus(limit=1)                  # selects q_tea only
        self.assertEqual([q.id for q in corpus.questions], ["q_tea"])
        self.assertEqual(len(corpus.documents), 3)
        twin = corpus.documents[support.key_of("answer_tea_1")]
        self.assertEqual(twin.date, "2023/04/01 (Sat) 07:00")     # from q_count, which is not selected
        bike = corpus.documents[support.key_of("noise_bike_3")]
        self.assertEqual(bike.date, "2023/05/01 (Mon) 08:00")
        q_tea = corpus.questions[0]
        self.assertEqual(dataset.date_for(q_tea, twin), "2023/05/02 (Tue) 09:00")
        self.assertEqual(dataset.date_for(q_tea, bike), "2023/05/10 (Wed) 08:00")

    def test_date_for_falls_back_to_document_date(self):
        corpus = support.fixture_corpus()
        q_rain = next(q for q in corpus.questions if q.id == "q_rain")
        bike = corpus.documents[support.key_of("noise_bike_3")]
        self.assertEqual(dataset.date_for(q_rain, bike), "2023/05/01 (Mon) 08:00")

    def test_int_answer_and_abstention_flag(self):
        by_id = {q.id: q for q in support.fixture_corpus().questions}
        self.assertEqual(by_id["q_count"].answer, "3")
        self.assertTrue(by_id["q_parrot_abs"].is_abstention)
        self.assertFalse(by_id["q_tea"].is_abstention)

    def test_selection_is_stratified_and_deterministic(self):
        ids = lambda n: [q.id for q in support.fixture_corpus(limit=n).questions]
        self.assertEqual(ids(2), ["q_tea", "q_count"])
        self.assertEqual(ids(3), ["q_tea", "q_count", "q_rain"])
        self.assertEqual(ids(4), ["q_tea", "q_count", "q_rain", "q_parrot_abs"])
        self.assertEqual(ids(99), ids(4))
        self.assertEqual([q.id for q in support.fixture_corpus().questions],
                         ["q_tea", "q_count", "q_parrot_abs", "q_rain"])        # no limit: file order

    def test_corpus_of_selection_is_union_of_haystacks(self):
        corpus = support.fixture_corpus(limit=2)
        self.assertEqual(set(corpus.documents), set().union(*(q.haystack for q in corpus.questions)))

    def test_to_iso(self):
        self.assertEqual(dataset.to_iso("2023/05/20 (Sat) 02:21"), "2023-05-20T02:21:00Z")

    def test_key_collision_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(dataset.DatasetError, "collision"):
                dataset.load(support.write_fixture(tmp), key_fn=lambda text: "dsame")

    def test_bad_input_raises_dataset_error_naming_the_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = os.path.join(tmp, "nope.json")
            with self.assertRaisesRegex(dataset.DatasetError, "nope.json"):
                dataset.load(missing)
            broken = os.path.join(tmp, "broken.json")
            pathlib.Path(broken).write_text("{not json", encoding="utf-8")
            with self.assertRaisesRegex(dataset.DatasetError, "broken.json"):
                dataset.load(broken)
            shapeless = os.path.join(tmp, "shape.json")
            pathlib.Path(shapeless).write_text(json.dumps([{"question_id": "q"}]), encoding="utf-8")
            with self.assertRaisesRegex(dataset.DatasetError, "question_type|missing"):
                dataset.load(shapeless)
            with self.assertRaisesRegex(dataset.DatasetError, "limit"):
                dataset.load(support.write_fixture(tmp), limit_questions=0)

    def test_malformed_session_or_turn_raises_dataset_error_naming_the_path(self):
        def broken(change):
            rows = json.loads(json.dumps(support.fixture_rows()))  # the fixture shares its turns
            change(rows[0])
            return rows

        def drop_content(row):
            del row["haystack_sessions"][1][0]["content"]

        def drop_role(row):
            del row["haystack_sessions"][1][1]["role"]

        def session_text(row):
            row["haystack_sessions"][0] = "SYNTHETIC: not a list of turns"

        def turn_text(row):
            row["haystack_sessions"][0][0] = "SYNTHETIC: not a turn"

        cases = ((drop_content, "content"), (drop_role, "role"), (session_text, "session"),
                 (turn_text, "turn"))
        with tempfile.TemporaryDirectory() as tmp:
            for change, word in cases:
                path = os.path.join(tmp, f"{change.__name__}.json")
                pathlib.Path(path).write_text(json.dumps(broken(change)), encoding="utf-8")
                with self.assertRaisesRegex(dataset.DatasetError, f"{change.__name__}.json.*q_tea"):
                    dataset.load(path)
                try:
                    dataset.load(path)
                except dataset.DatasetError as error:
                    self.assertIn(word, str(error))


if __name__ == "__main__":
    unittest.main()
