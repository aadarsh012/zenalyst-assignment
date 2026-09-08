#!/usr/bin/env python3
"""Generate a realistic applicant population for the demo.

Writes three files to build/demo/:

    paper.csv      applications to import through the paper channel
    online.ndjson  applications to POST one at a time, as the public would
    claims.csv     the certificate decisions counter staff would record

The population is deliberately awkward, because a clean one proves nothing:

  * roughly 8% of people apply more than once, split across all three matching
    tiers — the same identity number, the same name/birthday/phone with a
    different number, and a misspelling that only fuzzy matching will notice
  * about 40% claim local residence, 8% a disability, 3% ex-service
  * a slice are under age at the closing date and will be found ineligible
  * a slice have certificates nobody verifies, so they compete on open merit

Deterministic: --seed and --as-of together fix the population completely, so
two runs on different days produce byte-identical files and a demo can be
re-run and compared. Nothing here reads the wall clock.

    python3 scripts/generate-scheme-data.py --scheme MHS-2026 --count 4000
"""

import argparse
import csv
import datetime
import json
import pathlib
import random
import sys

# --- Verhoeff, so every generated identity number passes validation ----------

_D = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
      [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
      [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
      [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
      [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
_P = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
      [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
      [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
      [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]
_INV = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]

# The scheme window the demo creates its scheme with, and the default cutoff for receipt dates.
# All three are constants on purpose: the wall clock must not reach into this file.
SCHEME_OPENED = datetime.date(2026, 1, 1)
SCHEME_CLOSED = datetime.date(2026, 12, 31)
DEFAULT_AS_OF = datetime.date(2026, 6, 30)


def identity_number(index: int) -> str:
    """Twelve digits with a valid check digit, never beginning 0 or 1."""
    base = f"{2 + index % 8}{index:010d}"
    check = 0
    for i, digit in enumerate(reversed(base)):
        check = _D[check][_P[(i + 1) % 8][int(digit)]]
    return base + str(_INV[check])


GIVEN = ["Ramesh", "Sita", "Anil", "Meena", "Joseph", "Priya", "Rahul", "Lakshmi",
         "Farhan", "Kavita", "Suresh", "Anita", "Vikram", "Deepa", "Arjun", "Nisha"]
FAMILY = ["Kumar", "Devi", "Verma", "Iyer", "D'Souza", "Sharma", "Nair", "Reddy",
          "Khan", "Patel", "Chandra", "Menon", "Gupta", "Rao", "Singh", "Bose"]
CATEGORIES = ["GEN", "OBC", "SC", "ST", "EWS"]
CATEGORY_WEIGHTS = [0.42, 0.27, 0.15, 0.07, 0.09]
WARDS = [f"W-{n:02d}" for n in range(1, 13)]


def receipt_date(rng: random.Random, opened: datetime.date, latest: datetime.date) -> str:
    """A date in the scheme's window that has already happened.

    The span is passed in rather than derived from today's date. Deriving it from the clock made
    the range one day wider every day, which shifted every draw taken from `rng` afterwards and
    quietly broke the reproducibility this generator advertises.
    """
    span = max((latest - opened).days, 0)
    return (opened + datetime.timedelta(days=rng.randint(0, span))).isoformat()


def misspell(name: str, rng: random.Random) -> str:
    """A plausible transcription error: a doubled letter, as a clerk would make."""
    index = rng.randrange(1, len(name))
    return name[:index] + name[index] + name[index:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scheme", default="MHS-2026")
    parser.add_argument("--count", type=int, default=4000, help="distinct people")
    parser.add_argument("--seed", type=int, default=20260908)
    parser.add_argument("--as-of", default=DEFAULT_AS_OF.isoformat(),
                        help="latest receipt date to generate. Fixed, not today's date, so that "
                             "the population is reproducible. Must not be in the future: the "
                             "service rejects a form recorded before it was received.")
    parser.add_argument("--out", default="build/demo")
    args = parser.parse_args()

    as_of = datetime.date.fromisoformat(args.as_of)
    today = datetime.date.today()
    if as_of > today:
        print(f"--as-of {as_of} is in the future; today is {today}. Every generated form would "
              f"be rejected as received after it was recorded. Pass --as-of {today} or earlier.",
              file=sys.stderr)
        return 1

    opened = SCHEME_OPENED
    latest = min(as_of, SCHEME_CLOSED)

    rng = random.Random(args.seed)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    people, applications = [], []

    for i in range(args.count):
        given, family = rng.choice(GIVEN), rng.choice(FAMILY)
        # A slice are under age at the 2026 closing date and will be found ineligible.
        year = rng.randint(2010, 2012) if rng.random() < 0.02 else rng.randint(1955, 2005)
        person = {
            "name": f"{given} {family}",
            "dob": f"{year}-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d}",
            "id": identity_number(i),
            "phone": f"9{rng.randint(100000000, 999999999)}",
            "email": f"{given.lower()}.{family.lower().replace(chr(39), '')}{i}@example.com",
            "address": f"{rng.randint(1, 300)} {rng.choice(['Nehru', 'MG', 'Station', 'Park', 'Lake'])} Road",
            "ward": rng.choice(WARDS),
            "category": rng.choices(CATEGORIES, CATEGORY_WEIGHTS)[0],
            "gender": rng.choices(["FEMALE", "MALE", "OTHER"], [0.47, 0.51, 0.02])[0],
            "local": rng.random() < 0.40,
            "disability": rng.random() < 0.08,
            "ex_service": rng.random() < 0.03,
            "income": rng.choice([90000, 140000, 250000, 295000, 380000, 640000]),
        }
        people.append(person)
        applications.append(dict(person, dup_of=None, tier=None))

    # Roughly 8% apply more than once, across all three matching tiers.
    duplicate_count = int(args.count * 0.08)
    extra_index = args.count
    for person in rng.sample(people, duplicate_count):
        tier = rng.choices(["GOVERNMENT_ID", "NAME_DOB_PHONE", "PROBABLE"], [0.5, 0.3, 0.2])[0]
        again = dict(person, dup_of=person["id"], tier=tier)

        if tier == "GOVERNMENT_ID":
            # Same number, different contact details: someone unsure the first went through.
            again["phone"] = f"9{rng.randint(100000000, 999999999)}"
            again["email"] = f"second.{extra_index}@example.com"
        elif tier == "NAME_DOB_PHONE":
            # A different number entirely, but the same person behind it.
            again["id"] = identity_number(extra_index)
            again["email"] = f"third.{extra_index}@example.com"
        else:
            # A misspelling with nothing else in common: fuzzy matching's job, and a human's.
            again["id"] = identity_number(extra_index)
            given, family = person["name"].split(" ", 1)
            again["name"] = f"{given} {misspell(family, rng)}"
            again["phone"] = f"9{rng.randint(100000000, 999999999)}"
            again["email"] = f"fourth.{extra_index}@example.com"
        applications.append(again)
        extra_index += 1

    rng.shuffle(applications)

    # About a quarter arrive online; the rest are typed up from paper afterwards. Each application
    # gets a stable reference so that the claims file can name it: the identity number cannot be
    # used, because this system never stores one (ADR-0003).
    online, paper = [], []
    for a in applications:
        if rng.random() < 0.25:
            a["ref"] = f"ONLINE-{len(online):06d}"
            online.append(a)
        else:
            a["ref"] = f"RCPT-{len(paper):06d}"
            paper.append(a)

    with (out / "paper.csv").open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["paper_reference", "received_at", "full_name", "date_of_birth",
                         "government_id", "phone", "email", "address_line", "ward_code",
                         "category", "gender", "local_resident", "disability",
                         "ex_serviceperson", "annual_income"])
        # Receipt dates must be in the past: a form cannot have been handed in after it was typed
        # up, and the database says so.
        for a in paper:
            writer.writerow([a["ref"], receipt_date(rng, opened, latest),
                             a["name"], a["dob"], a["id"], a["phone"], a["email"],
                             a["address"], a["ward"], a["category"], a["gender"],
                             str(a["local"]).lower(), str(a["disability"]).lower(),
                             str(a["ex_service"]).lower(), a["income"]])

    with (out / "online.ndjson").open("w") as handle:
        for a in online:
            handle.write(json.dumps({
                "ref": a["ref"],
                "fullName": a["name"], "dateOfBirth": a["dob"], "governmentId": a["id"],
                "phone": a["phone"], "email": a["email"], "addressLine": a["address"],
                "wardCode": a["ward"], "category": a["category"], "gender": a["gender"],
                "localResident": str(a["local"]).lower(),
                "disability": str(a["disability"]).lower(),
                "exServiceperson": str(a["ex_service"]).lower(),
                "annualIncome": str(a["income"]),
            }) + "\n")

    # The claims a verifier would have to work through, named by the application's reference. The
    # demo resolves those to application numbers once the applications exist.
    with (out / "claims.csv").open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["ref", "claim"])
        for a in applications:
            # A tenth are left unverified on purpose: a real scheme freezes with a backlog, and
            # those applicants compete on open merit (ADR-0008).
            if rng.random() < 0.10:
                continue
            if a["category"] != "GEN":
                writer.writerow([a["ref"], "CATEGORY"])
            if a["category"] == "EWS":
                writer.writerow([a["ref"], "INCOME"])
            if a["local"]:
                writer.writerow([a["ref"], "LOCAL_RESIDENCE"])
            if a["disability"]:
                writer.writerow([a["ref"], "DISABILITY"])
            if a["ex_service"]:
                writer.writerow([a["ref"], "EX_SERVICE"])

    print(f"scheme          : {args.scheme}")
    print(f"distinct people : {args.count}")
    print(f"seed / as-of    : {args.seed} / {as_of}")
    print(f"applications    : {len(applications)}  ({duplicate_count} of them repeat submissions)")
    print(f"  paper         : {len(paper)}")
    print(f"  online        : {len(online)}")
    print(f"written to      : {out}/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
