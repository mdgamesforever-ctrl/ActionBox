#!/usr/bin/env python3
"""Processes the SMS Spam Collection (spam rows -> NOISE) and the SpamAssassin Public Corpus
spam tarballs (subject line only -> NOISE, since a full spam email body doesn't read like a
push-notification's text at all, but the subject line often does: "50% OFF EVERYTHING",
"You've been selected!"). Ham from SMS Spam Collection is written to a separate file for
manual/heuristic relabeling across the other 5 categories, per the task's instructions --
handled in relabel_sms_ham.py.
"""
import email
import glob
import json
import re

MIN_LEN, MAX_LEN = 6, 120


def process_sms_spam():
    spam, ham = [], []
    with open("tools/dataset_cache/raw/sms_spam.jsonl") as f:
        for line in f:
            row = json.loads(line)
            text = row["sms"].strip()
            if row["label"] == 1:
                if MIN_LEN <= len(text) <= 200:
                    spam.append(text)
            else:
                ham.append(text)
    return spam, ham


SUBJECT_NOISE = re.compile(r"^(re|fwd?):\s*", re.IGNORECASE)


def process_spamassassin():
    subjects = []
    seen = set()
    for path in sorted(glob.glob("tools/dataset_cache/raw/spamassassin/spam*/**/*", recursive=True)):
        import os
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "rb") as f:
                msg = email.message_from_binary_file(f)
        except Exception:
            continue
        subject = msg.get("Subject", "")
        if not subject:
            continue
        subject = str(subject)
        # Decode any RFC 2047 encoded-word subjects (=?charset?...?=) rather than keep the raw
        # escape sequences -- found necessary after the first pass showed garbled subjects.
        try:
            decoded_parts = email.header.decode_header(subject)
            subject = "".join(
                part.decode(enc or "utf-8", errors="ignore") if isinstance(part, bytes) else part
                for part, enc in decoded_parts
            )
        except Exception:
            pass
        subject = subject.strip().replace("\n", " ").replace("\t", " ")
        subject = re.sub(r"\s+", " ", subject)
        # Mailing-list bracket tags this corpus's source lists stamped on every subject
        # ("[ILUG]", "[ILUG-Social]", "[Razor-users]") -- not part of the actual spam content.
        # Must run BEFORE the Re:/Fwd: strip below, since a tag often precedes it
        # ("[ILUG-Social] re: ...") and would otherwise hide it from that regex's `^` anchor.
        subject = re.sub(r"^\[[\w-]+\]\s*", "", subject)
        subject = SUBJECT_NOISE.sub("", subject)
        # A trailing "N.NNN"-style version/score suffix this corpus's own tooling appended to
        # some subjects (e.g. "...in 30 days 10.206") -- an artifact of the corpus, not spam
        # content, found during spot-checking.
        subject = re.sub(r"\s+\d+\.\d+$", "", subject).strip()
        if not (MIN_LEN <= len(subject) <= MAX_LEN):
            continue
        # Drop subjects that are just symbol noise or a single non-ASCII-heavy token -- common
        # in older spam corpora from broken encodings, found during spot-checking.
        if not re.search(r"[a-zA-Z]{3,}", subject):
            continue
        # An email address baked into the subject is a corpus-anonymization/processing
        # artifact (e.g. "...zzzz@spamassassin.taint.org pviqg"), not real spam content.
        if re.search(r"@", subject):
            continue
        key = subject.lower()
        if key in seen:
            continue
        seen.add(key)
        subjects.append(subject)
    return subjects


def main():
    sms_spam, sms_ham = process_sms_spam()
    sa_subjects = process_spamassassin()

    print(f"SMS spam: {len(sms_spam)} usable / {len(sms_ham)} ham set aside")
    print(f"SpamAssassin subjects: {len(sa_subjects)} usable")

    with open("tools/dataset_cache/raw/sms_spam_clean.jsonl", "w") as f:
        for t in sms_spam:
            f.write(json.dumps({"text": t}) + "\n")
    with open("tools/dataset_cache/raw/sms_ham_raw.jsonl", "w") as f:
        for t in sms_ham:
            f.write(json.dumps({"text": t}) + "\n")
    with open("tools/dataset_cache/raw/spamassassin_subjects.jsonl", "w") as f:
        for t in sa_subjects:
            f.write(json.dumps({"text": t}) + "\n")

    print("\nSample SMS spam:")
    for t in sms_spam[:8]:
        print(" -", t)
    print("\nSample SpamAssassin subjects:")
    for t in sa_subjects[:15]:
        print(" -", t)


if __name__ == "__main__":
    main()
