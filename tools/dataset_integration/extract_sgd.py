#!/usr/bin/env python3
"""Extracts SYSTEM-turn utterances for specific dialogue acts from the downloaded
Schema-Guided Dialogue files, mapping CONFIRM and system REQUEST to ACTION (both are the
system asking the user to do something -- confirm a booking, supply missing info) and
NOTIFY_SUCCESS to FYI (a completed-status announcement). USER turns are skipped entirely --
they're the wrong direction (outbound queries a person sends, not a notification a person
receives), the same issue noted in the original dataset research for intent-classification
corpora.
"""
import glob
import json
import re

# Any turn whose ONLY action is one of these gets extracted; skip turns that mix in another
# act (e.g. CONFIRM + INFORM together) to avoid pulling in stray unrelated clauses.
WANTED = {"CONFIRM": "ACTION", "NOTIFY_SUCCESS": "FYI", "REQUEST": "ACTION"}

MIN_LEN, MAX_LEN = 15, 160


def main():
    counts = {}
    seen = set()
    out = []
    for path in sorted(glob.glob("tools/dataset_cache/raw/sgd/*.json")):
        dialogues = json.load(open(path))
        for dlg in dialogues:
            for turn in dlg["turns"]:
                if turn["speaker"] != "SYSTEM":
                    continue
                acts = set()
                for frame in turn["frames"]:
                    for action in frame.get("actions", []):
                        acts.add(action["act"])
                if len(acts) != 1:
                    continue
                act = next(iter(acts))
                if act not in WANTED:
                    continue
                text = turn["utterance"].strip()
                if not (MIN_LEN <= len(text) <= MAX_LEN):
                    continue
                key = text.lower()
                if key in seen:
                    continue
                seen.add(key)
                label = WANTED[act]
                out.append({"text": text, "label": label, "source_act": act})
                counts[act] = counts.get(act, 0) + 1
    with open("tools/dataset_cache/raw/sgd_extracted.jsonl", "w") as f:
        for row in out:
            f.write(json.dumps(row) + "\n")
    print(counts)
    print(f"Total: {len(out)}")


if __name__ == "__main__":
    main()
