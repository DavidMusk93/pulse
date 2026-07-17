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
    def __init__(self, partition_time, data_size, ttl):
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
        self.path_exists = True
        self.ttl_expired = False

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
        self.assertIn("previous two", result["description"])

    def test_record_json_exposes_ttl_and_expiration_duration(self):
        record = Record(epoch("2026-07-01"), 1024, 7 * 86400)
        record.ttl_expired = True
        now = epoch("2026-07-18")

        result = MODULE.record_to_json(record, now)

        self.assertEqual(7.0, result["ttl_days"])
        self.assertEqual(10.0, result["expired_days"])
        self.assertEqual("TTL 7 days", result["ttl_description"])
        self.assertEqual("expired 10 days ago", result["expired_description"])


if __name__ == "__main__":
    unittest.main()
