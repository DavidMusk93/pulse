import datetime as dt
import importlib.util
import json
import os
import unittest


SCRIPT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "docs",
    "task",
    "analyze-block-layout.py",
)
SPEC = importlib.util.spec_from_file_location("analyze_block_layout", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Record(object):
    def __init__(self, partition_time, data_size, ttl, path_exists=True, future_partition=False):
        self.db_path = "/tmp/fringedb.db"
        self.tenant = "tenant"
        self.table = "table"
        self.path = "/opt/tiger/tide/node0/disk01/tenant/table/block"
        self.data_size = data_size
        self.layer = 0
        self.event_time = partition_time
        self.ttl = ttl
        self.disk_path = "/opt/tiger/tide/node0/disk01"
        self.disk_name = "disk01"
        self.node_name = "node0"
        self.partition_time = partition_time
        self.path_exists = path_exists
        self.ttl_expired = False
        self.future_partition = future_partition

    @property
    def table_key(self):
        return "{}.{}".format(self.tenant, self.table)


def epoch(value):
    parsed = dt.datetime.strptime(value, "%Y-%m-%d")
    return int((parsed - dt.datetime(1970, 1, 1)).total_seconds())


class AnalyzeBlockLayoutTest(unittest.TestCase):
    def test_compact_json_keeps_small_objects_on_one_line(self):
        value = {
            "blocks": 2158,
            "table_percent": 2.25,
            "disk_count": 28,
            "partition_date": "2026-07-17",
            "size": "16.81 TiB",
        }

        rendered = MODULE.compact_json(value)

        self.assertNotIn("\n", rendered)
        self.assertEqual(value, json.loads(rendered))

    def test_compact_json_keeps_execution_plan_leaf_on_one_line(self):
        value = MODULE.record_to_json(
            Record(epoch("2026-07-17"), 108 * 1024 * 1024, 5 * 86400),
            epoch("2026-07-17"),
        )

        rendered = MODULE.compact_json(value)

        self.assertNotIn("\n", rendered)
        self.assertTrue(rendered.startswith('{"table_id":"tenant.table","table":"table"'))

    def test_storage_retention_uses_previous_two_available_days(self):
        records = [
            Record(epoch("2026-07-15"), 100, 7 * 86400),
            Record(epoch("2026-07-16"), 100, 7 * 86400),
            Record(epoch("2026-07-17"), 400, 7 * 86400),
        ]

        result = MODULE.storage_retention_summary(records, 600, epoch("2026-07-17"))

        self.assertEqual(
            ["2026-07-15", "2026-07-16"],
            [item["partition_date"] for item in result["previous_two_partition_days"]],
        )
        self.assertEqual(2, result["baseline_days_available"])
        self.assertEqual(6.0, result["estimated_storage_days"])
        self.assertEqual("85.71%", result["ttl_waterline_ratio"])
        self.assertIn("previous two", result["description"])

    def test_table_waterline_ratio_uses_day_one_day_two_average_against_ttl(self):
        records = [
            Record(epoch("2026-07-15"), 100, 7 * 86400),
            Record(epoch("2026-07-16"), 100, 7 * 86400),
            Record(epoch("2026-07-17"), 400, 7 * 86400),
        ]

        result = MODULE.aggregate_table_layout(records, 50)[0]

        self.assertEqual("85.71%", result["ttl_waterline_ratio"])
        self.assertEqual("100 B", result["ttl_waterline_daily_capacity"])
        self.assertEqual("700 B", result["ttl_waterline_capacity"])
        self.assertNotIn("ttl_days", result)
        self.assertNotIn("ttl_waterline_ratio_percent", result)
        self.assertNotIn("ttl_waterline_daily_capacity_bytes", result)
        self.assertNotIn("ttl_waterline_capacity_bytes", result)

    def test_record_json_exposes_ttl_and_expiration_duration(self):
        record = Record(epoch("2026-07-01"), 1024, 7 * 86400)
        record.ttl_expired = True
        now = epoch("2026-07-18")

        result = MODULE.record_to_json(record, now)

        self.assertEqual(7.0, result["ttl_days"])
        self.assertEqual(10.0, result["expired_days"])
        self.assertEqual("TTL 7 days", result["ttl_description"])
        self.assertEqual("expired 10 days ago", result["expired_description"])

    def test_classify_cleanup_flags_future_partition_blocks(self):
        past = Record(epoch("2026-07-10"), 100, 30 * 86400)
        future = Record(epoch("2032-12-07"), 200, 30 * 86400, future_partition=True)

        plan = MODULE.classify_cleanup([past, future], [])

        self.assertEqual([], plan["ttl_expired"])
        self.assertEqual(1, len(plan["future_partition"]))
        self.assertIs(future, plan["future_partition"][0])

    def test_future_partition_enters_metadata_and_path_delete_plan(self):
        now = epoch("2026-07-21")
        future = Record(epoch("2032-12-07"), 200, 30 * 86400, future_partition=True)
        plan = MODULE.classify_cleanup([future], [])

        summary = MODULE.plan_summary(plan, now)
        self.assertEqual(1, summary["future_partition"]["count"])
        self.assertEqual(1, summary["future_partition"]["metadata_delete_count"])
        self.assertEqual(1, summary["future_partition"]["path_delete_count"])
        self.assertIsNotNone(summary["future_partition"]["furthest_ahead_for"])

        file_items = MODULE.file_delete_items(plan, "/opt/tiger/tide")
        self.assertEqual(1, len(file_items))
        self.assertEqual("future_partition", file_items[0]["type"])

    def test_record_json_exposes_future_partition_ahead(self):
        record = Record(epoch("2032-12-07"), 200, 30 * 86400, future_partition=True)
        now = epoch("2026-07-21")

        result = MODULE.record_to_json(record, now)

        self.assertTrue(result["future_partition"])
        self.assertIn("ahead of now", result["partition_ahead_description"])


if __name__ == "__main__":
    unittest.main()
