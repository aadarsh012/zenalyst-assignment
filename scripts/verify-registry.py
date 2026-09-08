#!/usr/bin/env python3
"""Independently verify a frozen housing register.

Written to be read by somebody who does not trust the service that produced the data. It uses
nothing but the published rows and Python's standard library: no client, no shared code, no
appeal to the authority's own arithmetic.

    python3 scripts/verify-registry.py <registry-root> [application-no]

It answers two questions:

  1. Do the published rows actually produce the published root?
  2. Does one applicant's inclusion proof check out on its own?

The Merkle construction follows RFC 6962 and is reproduced here in fifteen lines, which is the
point: a commitment nobody can independently recompute is not a commitment.
"""

import hashlib
import json
import sys
import urllib.request

BASE = "http://localhost:8080"


def leaf_hash(payload: str) -> bytes:
    """RFC 6962 leaf: SHA-256(0x00 || data)."""
    return hashlib.sha256(b"\x00" + payload.encode("utf-8")).digest()


def internal_hash(left: bytes, right: bytes) -> bytes:
    """RFC 6962 internal node: SHA-256(0x01 || left || right)."""
    return hashlib.sha256(b"\x01" + left + right).digest()


def merkle_root(payloads: list[str]) -> str:
    if not payloads:
        return hashlib.sha256(b"").hexdigest()
    level = [leaf_hash(p) for p in payloads]
    while len(level) > 1:
        nxt = []
        for i in range(0, len(level), 2):
            # An odd node is promoted, never duplicated: duplicating lets two distinct
            # candidate lists collapse to one root (Bitcoin's CVE-2012-2459).
            nxt.append(internal_hash(level[i], level[i + 1]) if i + 1 < len(level) else level[i])
        level = nxt
    return level[0].hex()


def verify_proof(payload: str, proof: list[dict], expected_root: str) -> bool:
    running = leaf_hash(payload)
    for step in proof:
        sibling = bytes.fromhex(step["hash"])
        running = (internal_hash(sibling, running) if step["side"] == "LEFT"
                   else internal_hash(running, sibling))
    return running.hex() == expected_root


def fetch(path: str):
    with urllib.request.urlopen(BASE + path) as response:
        return json.load(response)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = sys.argv[1]

    candidates = fetch(f"/api/v1/registry/{root}/candidates")
    payloads = [c["canonicalJson"] for c in candidates]

    print(f"published root : {root}")
    print(f"rows published : {len(payloads)}")

    recomputed = merkle_root(payloads)
    print(f"recomputed root: {recomputed}")
    if recomputed != root:
        print("\nMISMATCH — the published rows do not produce the published root.")
        return 1
    print("\nThe published rows produce the published root.")

    if len(sys.argv) > 2:
        application_no = sys.argv[2]
        proof = fetch(f"/api/v1/registry/{root}/proof/{application_no}")
        ok = verify_proof(proof["canonicalJson"], proof["proof"], root)
        print(f"\ninclusion proof for {application_no}: {len(proof['proof'])} hashes")
        print(f"  row  : {proof['canonicalJson']}")
        print(f"  check: {'VERIFIED — this row was among the inputs' if ok else 'FAILED'}")
        if not ok:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
