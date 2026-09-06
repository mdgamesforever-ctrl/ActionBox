#!/usr/bin/env python3
"""Heuristic triage of the SMS Spam Collection's ham messages into ActionBox's 6 categories.
Conservative by design: most of this corpus is genuine peer-to-peer personal chit-chat with no
notification-shaped speech act at all (confirmed by manual inspection -- see the session
report), so this drops far more than it keeps rather than force-fitting casual conversation
into a category it doesn't belong in.
"""
import json
import re

MIN_LEN, MAX_LEN = 8, 140

ACTION_CUES = re.compile(
    r"\b(can (?:u|you) (?:send|call|bring|pick|check|come|buy|get)|"
    r"pls (?:send|call|bring|come|check)|please (?:send|call|bring|come|check)|"
    r"(?:send|call|bring) (?:me|it) (?:asap|now|pls|please))\b", re.IGNORECASE
)
WAITING_CUES = re.compile(
    r"\b(i'll (?:call|send|check|be there|come)|ill (?:call|send|check|be there|come)|"
    r"i will (?:call|send|check|come)|on my way|omw|will call|will send)\b", re.IGNORECASE
)
REPLY_CUES = re.compile(
    r"\b(what (?:u|you) doin|wyd|hows it going|how are (?:u|you)|"
    r"u there|are u (?:free|ok|okay|there)|lemme know|let me know|"
    r"what do u think|call me when|text me when)\b", re.IGNORECASE
)
FYI_CUES = re.compile(
    r"\b(fyi|just (?:to let|letting) you know|reminder:|note:|for your information)\b",
    re.IGNORECASE
)
DEADLINE_CUES = re.compile(
    r"\b(due (?:by|on|today|tomorrow)|deadline|expires?|last day|cutoff|"
    r"by (?:today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b",
    re.IGNORECASE
)

# Casual conversational fillers/reactions that are NOT notification-shaped even when they
# happen to contain a cue word -- dropped outright rather than mislabeled, found necessary
# after the first triage pass let too much pure chit-chat through.
CHITCHAT_NOISE = re.compile(
    r"\b(lol|lmao|haha|omg\b.{0,5}$|k\.\.\.|joking|jk\b)\b", re.IGNORECASE
)


def triage(text: str):
    if not (MIN_LEN <= len(text) <= MAX_LEN):
        return None
    if CHITCHAT_NOISE.search(text):
        return None
    if DEADLINE_CUES.search(text):
        return "DEADLINE"
    if ACTION_CUES.search(text):
        return "ACTION"
    if WAITING_CUES.search(text):
        return "WAITING"
    if FYI_CUES.search(text):
        return "FYI"
    if REPLY_CUES.search(text):
        return "REPLY"
    return None


def main():
    counts = {}
    seen = set()
    out = []
    with open("tools/dataset_cache/raw/sms_ham_raw.jsonl") as f:
        for line in f:
            text = json.loads(line)["text"].strip()
            label = triage(text)
            if not label:
                continue
            key = text.lower()
            if key in seen:
                continue
            seen.add(key)
            out.append({"text": text, "label": label})
            counts[label] = counts.get(label, 0) + 1
    with open("tools/dataset_cache/raw/sms_ham_triaged.jsonl", "w") as f:
        for row in out:
            f.write(json.dumps(row) + "\n")
    print(counts)
    print(f"Total: {len(out)}")


if __name__ == "__main__":
    main()
