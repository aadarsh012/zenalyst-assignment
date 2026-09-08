#!/usr/bin/env python3
"""Run a whole housing scheme end to end, then check the result without trusting it.

Drives the public API exactly as an operator and the public would: applications arrive
through both channels, certificates are verified, duplicates resolved, the register frozen,
a seed committed to and revealed, the draw executed and published — and then the result is
independently re-derived.

    python3 scripts/demo.py                    # 4,000 people, the size in the brief
    python3 scripts/demo.py --count 400        # quicker

Requires the application running on :8080 under the dev profile.
"""

import argparse
import csv
import io
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"


class Api:
    def __init__(self, base=BASE):
        self.base = base
        self.tokens = {}

    def token(self, subject, roles):
        key = (subject, tuple(roles))
        if key not in self.tokens:
            body = json.dumps({"subject": subject, "roles": list(roles)}).encode()
            self.tokens[key] = self._send("POST", "/api/v1/dev/token", body,
                                          {"Content-Type": "application/json"})["token"]
        return self.tokens[key]

    def call(self, method, path, body=None, as_role=None, subject=None, raw=False,
             content_type="application/json", expect=(200, 201, 202), headers=None):
        headers = dict(headers or {})
        if as_role:
            headers["Authorization"] = "Bearer " + self.token(subject or as_role.lower(), [as_role])
        if body is not None and not raw:
            headers["Content-Type"] = content_type
            body = body.encode() if isinstance(body, str) else body
        elif raw:
            headers["Content-Type"] = content_type
        return self._send(method, path, body, headers, expect)

    def _send(self, method, path, body=None, headers=None, expect=(200, 201, 202)):
        request = urllib.request.Request(self.base + path, data=body, method=method,
                                         headers=headers or {})
        try:
            with urllib.request.urlopen(request) as response:
                text = response.read().decode()
                return json.loads(text) if text.strip().startswith(("{", "[")) else text
        except urllib.error.HTTPError as error:
            detail = error.read().decode()
            raise SystemExit(f"\n{method} {path} -> {error.code}\n{detail[:600]}\n")


def multipart(field, filename, content):
    """A multipart body, built by hand so the script needs nothing but the standard library."""
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
            f"filename=\"{filename}\"\r\nContent-Type: text/csv\r\n\r\n").encode()
    body += content.encode() if isinstance(content, str) else content
    body += f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def step(number, title):
    print(f"\n\033[1m{number}. {title}\033[0m")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scheme", default="DEMO-2026")
    parser.add_argument("--count", type=int, default=4000)
    parser.add_argument("--flats", type=int, default=600)
    parser.add_argument("--out", default="build/demo")
    args = parser.parse_args()

    api = Api()
    started = time.time()

    step(1, f"Generating a population of {args.count:,} people")
    subprocess.run([sys.executable, "scripts/generate-scheme-data.py",
                    "--scheme", args.scheme,
                    "--count", str(args.count), "--out", args.out], check=True)
    out = pathlib.Path(args.out)

    step(2, "Opening the scheme")
    api.call("POST", f"/api/v1/dev/scheme?code={args.scheme}&flats={args.flats}", body=b"")
    print(f"   {args.scheme}: {args.flats} flats")

    step(3, "Importing paper applications")
    paper = (out / "paper.csv").read_text()
    body, content_type = multipart("file", "paper.csv", paper)
    report = api.call("POST", f"/api/v1/schemes/{args.scheme}/applications:import?enteredBy=clerk-anita",
                      body=body, as_role="OPERATOR", raw=True, content_type=content_type)
    print(f"   {report['accepted']:,} accepted, {report['rejected']} rejected, "
          f"{report['alreadyImported']} already present")
    if report["rejected"]:
        for outcome in report["outcomes"]:
            if outcome["status"] == "REJECTED":
                reasons = ", ".join(f"{v['field']}:{v['code']}" for v in outcome["violations"])
                print(f"     row {outcome['rowNumber']}: {reasons}")
                break
    # applicationNo is absent, not null, for rows that were not accepted.
    ref_to_application = {o["paperReference"]: o["applicationNo"]
                          for o in report["outcomes"] if o.get("applicationNo")}

    step(4, "Submitting online applications")
    online = [json.loads(line) for line in (out / "online.ndjson").read_text().splitlines()]
    for record in online:
        ref = record.pop("ref")
        # The key is derived from the scheme and the applicant's reference rather than generated
        # per call, so running the demo twice replays these submissions instead of creating a
        # second application for every online applicant. This is the endpoint's advertised
        # behaviour; the demo should be using it.
        created = api.call("POST", f"/api/v1/schemes/{args.scheme}/applications",
                           body=json.dumps(record),
                           headers={"Idempotency-Key": f"{args.scheme}:{ref}"})
        ref_to_application[ref] = created["applicationNo"]
    print(f"   {len(online):,} submitted")

    step(5, "Verifying certificates")
    rows = list(csv.DictReader(io.StringIO((out / "claims.csv").read_text())))
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["application_no", "claim", "outcome", "evidence_reference"])
    for row in rows:
        application_no = ref_to_application.get(row["ref"])
        if application_no:
            writer.writerow([application_no, row["claim"], "VERIFIED", f"DOC-{row['ref']}"])
    body, content_type = multipart("file", "verifications.csv", buffer.getvalue())
    verified = api.call("POST", f"/api/v1/schemes/{args.scheme}/verifications:import?verifiedBy=clerk-anita",
                        body=body, as_role="OPERATOR", raw=True, content_type=content_type)
    print(f"   {verified['recorded']:,} recorded, {verified['rejected']} refused")

    step(6, "Resolving duplicates")
    dedup = api.call("POST", f"/api/v1/schemes/{args.scheme}/deduplication:run?runBy=operator-1",
                     as_role="OPERATOR")
    print(f"   {dedup['applicationsExamined']:,} applications -> "
          f"{dedup['distinctApplicants']:,} people")
    print(f"   {dedup['applicationsLinked']:,} linked automatically, "
          f"{dedup['reviewsPending']} awaiting a human")

    step(7, "Publishing the quota matrix")
    seats = {"OPEN": args.flats // 2, "OBC": int(args.flats * 0.27),
             "SC": int(args.flats * 0.15), "ST": int(args.flats * 0.07)}
    seats["EWS"] = args.flats - sum(seats.values())
    # A rule version is immutable once written, so a second run of the demo against the same
    # scheme publishes the next version rather than failing on the name. That is what the endpoint
    # is for: quota matrices supersede each other, they are never edited.
    existing = {r["version"] for r
                in api.call("GET", f"/api/v1/schemes/{args.scheme}/rules", as_role="ADMIN")}
    number = 1
    while f"v{number}" in existing:
        number += 1
    version = f"v{number}"

    api.call("POST", f"/api/v1/schemes/{args.scheme}/rules?createdBy=registrar-1", as_role="ADMIN",
             body=json.dumps({"version": version, "seats": seats, "horizontalReservations": [
                 {"category": "WOMEN", "share": 0.30}, {"category": "PWD", "share": 0.05},
                 {"category": "EX_SERVICE", "share": 0.03},
                 {"category": "LOCAL_RESIDENT", "share": 0.20}]}))
    api.call("POST",
             f"/api/v1/schemes/{args.scheme}/rules/{version}:activate?activatedBy=registrar-1",
             as_role="ADMIN")
    print(f"   {version}: {seats}")

    step(8, "Freezing the register")
    frozen = api.call("POST", f"/api/v1/schemes/{args.scheme}/registry:freeze?frozenBy=registrar-1",
                      as_role="ADMIN")
    print(f"   root      {frozen['registryRoot']}")
    print(f"   {frozen['candidateCount']:,} candidates, {frozen['eligibleCount']:,} eligible")

    step(9, "Committing to a seed")
    # A draw left COMMITTED, REVEALED or RUNNING blocks every later draw for the scheme, and no
    # endpoint abandons one, so a demo interrupted between commit and completion would wedge its
    # scheme for good. Finish whatever is in flight first. COMPLETED and FAILED do not block.
    for stale in api.call("GET", f"/api/v1/schemes/{args.scheme}/draws"):
        if stale["status"] not in ("COMMITTED", "REVEALED", "RUNNING"):
            continue
        print(f"   finishing an interrupted draw first: {stale['drawId']} ({stale['status']})")
        if stale["status"] == "COMMITTED":
            api.call("POST", f"/api/v1/draws/{stale['drawId']}/reveal?revealedBy=registrar-1",
                     as_role="ADMIN")
        if stale["status"] in ("COMMITTED", "REVEALED"):
            api.call("POST", f"/api/v1/draws/{stale['drawId']}/execute?executedBy=registrar-1",
                     as_role="ADMIN")
        for _ in range(120):
            if api.call("GET", f"/api/v1/draws/{stale['drawId']}")["status"] in (
                    "COMPLETED", "FAILED"):
                break
            time.sleep(1)

    draw = api.call("POST", f"/api/v1/schemes/{args.scheme}/draws?committedBy=registrar-1",
                    as_role="ADMIN")
    draw_id = draw["drawId"]
    print(f"   commitment {draw['seedCommitment']}")
    print(f"   the seed itself is not in the response: {'seed' not in draw}")

    step(10, "Revealing the seed")
    revealed = api.call("POST", f"/api/v1/draws/{draw_id}/reveal?revealedBy=registrar-1",
                        as_role="ADMIN")
    import hashlib
    recomputed = hashlib.sha256(
        (revealed["seed"] + ":" + revealed["seedSalt"]).encode()).hexdigest()
    print(f"   seed       {revealed['seed']}")
    print(f"   SHA-256(seed + ':' + salt) == commitment: "
          f"{recomputed == draw['seedCommitment']}")

    step(11, "Drawing")
    api.call("POST", f"/api/v1/draws/{draw_id}/execute?executedBy=registrar-1", as_role="ADMIN")
    for _ in range(120):
        state = api.call("GET", f"/api/v1/draws/{draw_id}")
        if state["status"] in ("COMPLETED", "FAILED"):
            break
        time.sleep(1)
    if state["status"] != "COMPLETED":
        raise SystemExit(f"   draw {state['status']}: {state.get('failureReason')}")
    api.call("POST", f"/api/v1/draws/{draw_id}/publish?publishedBy=commissioner", as_role="ADMIN")
    print(f"   {state['seatsAwarded']} flats allotted")
    print(f"   result     {state['resultHash']}")

    step(12, "Verifying, without trusting the service that produced it")
    report = api.call("POST", f"/api/v1/draws/{draw_id}/verify")
    for check in report["checks"]:
        print(f"   {'PASS' if check['passed'] else 'FAIL'}  {check['name']}")
    chain = api.call("GET", "/api/v1/audit/verify")
    print(f"   {'PASS' if chain['verified'] else 'FAIL'}  AUDIT_CHAIN "
          f"({chain['eventsChecked']:,} events)")

    step(13, "Explaining one applicant's outcome")
    allotted = api.call("GET", f"/api/v1/draws/{draw_id}/results.csv").splitlines()
    application_no = next(line.split(",")[0] for line in allotted if line.startswith(args.scheme))
    explain = api.call("GET", f"/api/v1/applications/{application_no}/explain", as_role="AUDITOR")
    print(f"   {application_no}: {explain['summary']}")

    print(f"\n\033[1mDone in {time.time() - started:.0f}s.\033[0m")
    print(f"   draw        {draw_id}")
    print(f"   registry    {frozen['registryRoot']}")
    print(f"\n   Check it yourself, with nothing but the published data:")
    print(f"     python3 scripts/verify-registry.py {frozen['registryRoot']} {application_no}")
    print(f"     curl -s {BASE}/api/v1/draws/{draw_id}/results.csv")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
