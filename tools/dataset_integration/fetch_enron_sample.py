#!/usr/bin/env python3
"""Fetches a scattered sample (not the first N sequential rows, to avoid over-representing a
single thread/employee) of the raw Enron corpus via HF's datasets-server, parses out the
Subject and first body sentence from each raw email blob, and writes candidates for manual
relabeling. Per the task, this is a SAMPLE of the ~500K corpus, not an attempt to process all
of it."""
import json
import random
import re
import time
import urllib.request
import urllib.parse

API = "https://datasets-server.huggingface.co/rows"
DATASET = "snoop2head/enron_aeslc_emails"
TOTAL = 535703
random.seed(42)


def fetch_page(offset: int, length: int = 100):
    params = {"dataset": DATASET, "config": "default", "split": "train", "offset": offset, "length": length}
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                return json.load(resp)
        except Exception as e:
            print(f"  retry {attempt} for offset {offset}: {e}")
            time.sleep(2)
    raise RuntimeError(f"failed at offset {offset}")


def parse_email(text: str):
    subject_match = re.search(r"^Subject:[ \t]*(.*)$", text, re.MULTILINE)
    body_match = re.search(r"^Body:[ \t]*\n(.*)", text, re.DOTALL | re.MULTILINE)
    subject = subject_match.group(1).strip() if subject_match else ""
    body = body_match.group(1).strip() if body_match else ""
    # First non-empty line of the body as the "opening sentence".
    opening = ""
    for line in body.split("\n"):
        line = line.strip()
        if line:
            opening = line
            break
    return subject, opening


def main():
    n_pages = 40
    offsets = sorted(random.sample(range(0, TOTAL - 100), n_pages))
    candidates = []
    for i, offset in enumerate(offsets):
        data = fetch_page(offset)
        for row in data["rows"]:
            subject, opening = parse_email(row["row"]["text"])
            if subject and len(subject) < 100 and opening and len(opening) < 200:
                candidates.append({"subject": subject, "opening": opening})
        print(f"page {i+1}/{n_pages} (offset {offset}): {len(candidates)} candidates so far")
    with open("tools/dataset_cache/raw/enron_sample.jsonl", "w") as f:
        for c in candidates:
            f.write(json.dumps(c) + "\n")
    print(f"Wrote {len(candidates)} candidates")


if __name__ == "__main__":
    main()
