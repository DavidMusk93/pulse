#!/usr/bin/env python3
import argparse
import ipaddress
import re
import sys
from typing import Optional


def parse_host(line: str) -> Optional[str]:
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    parts = re.split(r"\s+", line)
    host = parts[-1]
    if host == "total" or host.startswith("total="):
        return None
    return host


def sort_key(host: str):
    try:
        return (0, int(ipaddress.ip_address(host)), host)
    except ValueError:
        return (1, host, host)


def leader_url(host: str, port: int) -> str:
    try:
        ip = ipaddress.ip_address(host)
        if ip.version == 6:
            return f"http://[{host}]:{port}"
    except ValueError:
        if ":" in host:
            return f"http://[{host}]:{port}"
    return f"http://{host}:{port}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Pulse static heartbeat group plan CSV.")
    parser.add_argument("--cluster", required=True, help="Fallback cluster/tag name used in group_id.")
    parser.add_argument("--area", default="unknown", help="Area name used in group_id before tide metadata is known.")
    parser.add_argument("--group-size", type=int, default=7, help="Maximum agents per group.")
    parser.add_argument("--leader-port", type=int, default=9977, help="Leader HTTP receiver port.")
    args = parser.parse_args()

    hosts = sorted({host for line in sys.stdin if (host := parse_host(line))}, key=sort_key)
    if args.group_size < 1:
        raise SystemExit("--group-size must be >= 1")

    print("host,group_id,group_mode,leader_url,group_members")
    for group_index in range(0, len(hosts), args.group_size):
        members = hosts[group_index : group_index + args.group_size]
        shard = group_index // args.group_size
        group_id = f"{args.cluster}/{args.area}/{shard:03d}"
        leader = members[0]
        url = leader_url(leader, args.leader_port)
        member_value = ";".join(members)
        for host in members:
            mode = "leader" if host == leader else "follower"
            print(f"{host},{group_id},{mode},{url},{member_value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
