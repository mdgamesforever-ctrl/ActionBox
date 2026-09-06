#!/usr/bin/env python3
"""Heuristic triage of the Enron opening-sentence sample into ActionBox's 6 categories, for
manual spot-check afterward. Deliberately conservative: drops anything that doesn't clearly
fit one category rather than force-fitting it, and skips content that isn't email-notification-
shaped at all (long forwarded threads, meeting minutes, pure business prose with no clear
speech-act). This is a first-pass triage, not a final label -- see the spot-check step."""
import json
import re

FORWARD_NOISE = re.compile(r"^-+\s*forwarded by", re.IGNORECASE)
CONFIDENTIAL_NOISE = re.compile(r"confidential|please disregard|out of office", re.IGNORECASE)
# Quoted-printable MIME artifacts ("=20" for a space, a trailing "=" at line-wrap points) that
# some raw Enron bodies were never decoded past -- a real data-quality issue found during
# inspection, not present in every row, so rows containing it are dropped rather than guessing
# at a decode.
MIME_ARTIFACT = re.compile(r"=[0-9A-F]{2}\b|=\s*$")

ACTION_CUES = re.compile(
    r"\b(please (?:send|call|review|confirm|forward|sign|approve|complete|provide|let me know)|"
    r"can you (?:send|call|review|confirm|forward|check|provide)|"
    r"need (?:you to|this|the)|could you)\b", re.IGNORECASE
)
FYI_CUES = re.compile(
    r"\b(fyi|attached is|attached please find|for your (?:records|information)|"
    r"confirmed|has been (?:sent|completed|approved|finalized)|is now|was (?:sent|completed|approved))\b",
    re.IGNORECASE
)
WAITING_CUES = re.compile(
    r"\b(i'll (?:send|get back|check|call|have)|i will (?:send|get back|check|call|have)|"
    r"working on (?:it|this)|will (?:send|get back|follow up|have)|let me (?:check|get back))\b",
    re.IGNORECASE
)
REPLY_CUES = re.compile(
    r"\b(let me know|what do you think|thoughts\??$|any (?:thoughts|update)|"
    r"are you (?:free|available)|does this work)\b", re.IGNORECASE
)
DEADLINE_CUES = re.compile(
    r"\b(due (?:by|on|today|tomorrow)|deadline|by (?:monday|tuesday|wednesday|thursday|friday|"
    r"end of (?:day|week|month))|expires?|cutoff)\b", re.IGNORECASE
)

MIN_LEN, MAX_LEN = 8, 140


def triage(opening: str):
    if FORWARD_NOISE.search(opening) or CONFIDENTIAL_NOISE.search(opening):
        return None
    if MIME_ARTIFACT.search(opening):
        return None
    if not (MIN_LEN <= len(opening) <= MAX_LEN):
        return None
    # Real emails wrap mid-sentence across newlines constantly; taking only the first line (as
    # fetch_enron_sample.py does for a cheap "opening sentence") routinely cuts a clause off
    # mid-word/mid-thought. Requiring terminal punctuation is a blunt but effective filter for
    # "this line is actually a complete thought" -- found necessary after spot-checking the
    # first triage pass turned up truncated fragments like "I will have a fully developed" and
    # "pick you up at the Four".
    if not opening.rstrip().endswith((".", "!", "?")):
        return None
    if DEADLINE_CUES.search(opening):
        return "DEADLINE"
    if ACTION_CUES.search(opening):
        return "ACTION"
    if WAITING_CUES.search(opening):
        return "WAITING"
    if FYI_CUES.search(opening):
        return "FYI"
    if REPLY_CUES.search(opening):
        return "REPLY"
    return None


def main():
    counts = {}
    labeled = []
    with open("tools/dataset_cache/raw/enron_sample.jsonl") as f:
        for line in f:
            row = json.loads(line)
            label = triage(row["opening"])
            if label:
                labeled.append({"text": row["opening"], "label": label, "subject": row["subject"]})
                counts[label] = counts.get(label, 0) + 1
    seen = set()
    deduped = []
    for row in labeled:
        key = row["text"].lower().strip()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(row)
    with open("tools/dataset_cache/raw/enron_triaged.jsonl", "w") as f:
        for row in deduped:
            f.write(json.dumps(row) + "\n")
    print(f"Triaged (pre-dedup): {counts}")
    print(f"After dedup: {len(deduped)} total")
    dedup_counts = {}
    for row in deduped:
        dedup_counts[row["label"]] = dedup_counts.get(row["label"], 0) + 1
    print(dedup_counts)


if __name__ == "__main__":
    main()
