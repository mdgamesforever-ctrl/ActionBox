#!/usr/bin/env python3
"""Generic fetcher for HuggingFace's datasets-server /rows API -- paginates through an entire
dataset split and writes it to a local JSONL file. No auth needed; this endpoint is public for
any dataset HF has already converted to Parquet (nearly all of them)."""
import json
import sys
import time
import urllib.request
import urllib.parse

API = "https://datasets-server.huggingface.co/rows"


def fetch_all(dataset: str, config: str, split: str, out_path: str, page_size: int = 100, max_rows: int = None):
    offset = 0
    all_rows = []
    while True:
        params = {"dataset": dataset, "config": config, "split": split, "offset": offset, "length": page_size}
        url = API + "?" + urllib.parse.urlencode(params)
        for attempt in range(5):
            try:
                with urllib.request.urlopen(url, timeout=30) as resp:
                    data = json.load(resp)
                break
            except Exception as e:
                print(f"  retry {attempt} for offset {offset}: {e}", file=sys.stderr)
                time.sleep(2)
        else:
            raise RuntimeError(f"failed to fetch offset {offset}")
        rows = data.get("rows", [])
        if not rows:
            break
        all_rows.extend(r["row"] for r in rows)
        total = data.get("num_rows_total", 0)
        print(f"  fetched {len(all_rows)}/{total}", file=sys.stderr)
        offset += page_size
        if max_rows and len(all_rows) >= max_rows:
            all_rows = all_rows[:max_rows]
            break
        if offset >= total:
            break
    with open(out_path, "w") as f:
        for row in all_rows:
            f.write(json.dumps(row) + "\n")
    print(f"Wrote {len(all_rows)} rows to {out_path}")


if __name__ == "__main__":
    dataset, config, split, out_path = sys.argv[1:5]
    max_rows = int(sys.argv[5]) if len(sys.argv) > 5 else None
    fetch_all(dataset, config, split, out_path, max_rows=max_rows)
