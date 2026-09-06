#!/usr/bin/env python3
"""Sample utterances per dialogue-act type from downloaded Schema-Guided Dialogue files, for
manual inspection before deciding which acts map to which ActionBox category."""
import glob
import json
from collections import defaultdict

samples = defaultdict(list)
counts = defaultdict(int)

for path in sorted(glob.glob("tools/dataset_cache/raw/sgd/*.json")):
    dialogues = json.load(open(path))
    for dlg in dialogues:
        for turn in dlg["turns"]:
            acts_here = set()
            for frame in turn["frames"]:
                for action in frame.get("actions", []):
                    acts_here.add(action["act"])
            for act in acts_here:
                counts[(turn["speaker"], act)] += 1
                if len(samples[(turn["speaker"], act)]) < 6:
                    samples[(turn["speaker"], act)].append(turn["utterance"])

for key in sorted(counts, key=lambda k: -counts[k]):
    speaker, act = key
    print(f"\n=== {speaker} / {act} (n={counts[key]}) ===")
    for s in samples[key]:
        print(f"  - {s}")
