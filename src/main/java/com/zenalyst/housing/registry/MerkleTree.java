package com.zenalyst.housing.registry;

import com.zenalyst.housing.platform.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A Merkle tree over the frozen candidate rows.
 *
 * <h2>What it is for</h2>
 *
 * <p>Publishing one 64-character root commits the authority to an exact set of candidates with
 * exact attributes. Once that root is in a newspaper, nobody can add a candidate, remove one, or
 * change somebody's category without the root changing.
 *
 * <p>The root alone would be enough for that. The tree earns its keep by also giving each
 * applicant an <strong>inclusion proof</strong>: a handful of hashes with which they can verify,
 * on their own, that their row was among the inputs — without downloading the register, and
 * without taking our word for anything.
 *
 * <h2>Construction</h2>
 *
 * <p>Following RFC 6962 (Certificate Transparency), with domain separation between leaves and
 * internal nodes:
 *
 * <pre>
 *   leaf(data)          = SHA-256( 0x00 || data )
 *   internal(left,right) = SHA-256( 0x01 || left || right )
 * </pre>
 *
 * <p>The prefix bytes are not decoration. Without them a leaf whose contents happened to be two
 * concatenated digests would hash identically to the internal node above them, which lets an
 * attacker present a tree of a different shape as if it were the original — the second-preimage
 * attack RFC 6962 exists to prevent.
 *
 * <p>A level with an odd number of nodes promotes the last node unchanged rather than duplicating
 * it. Duplicating is the more commonly seen approach and carries the Bitcoin CVE-2012-2459 flaw:
 * two distinct candidate lists produce the same root, so a set of candidates would no longer be
 * uniquely committed to.
 *
 * <p>Leaf order is the caller's, and the caller sorts by application number — deterministic,
 * meaningful, and reproducible by anyone holding the published rows.
 *
 * <p>The class is pure: no Spring, no clock, no database.
 */
public final class MerkleTree {

    private static final byte LEAF_PREFIX = 0x00;
    private static final byte INTERNAL_PREFIX = 0x01;

    /** Root of a tree with no leaves. Explicit, so an empty register still has a stated root. */
    public static final String EMPTY_ROOT = Hashing.sha256Hex(new byte[0]);

    private final List<byte[]> leaves;
    private final List<List<byte[]>> levels;

    private MerkleTree(List<byte[]> leaves, List<List<byte[]>> levels) {
        this.leaves = leaves;
        this.levels = levels;
    }

    /** @param payloads the exact bytes committed to, one per candidate, in the order they are published */
    public static MerkleTree of(List<String> payloads) {
        List<byte[]> leaves = payloads.stream().map(MerkleTree::leafHash).toList();

        List<List<byte[]>> levels = new ArrayList<>();
        levels.add(leaves);
        List<byte[]> current = leaves;
        while (current.size() > 1) {
            current = nextLevel(current);
            levels.add(current);
        }
        return new MerkleTree(leaves, levels);
    }

    public String root() {
        if (leaves.isEmpty()) {
            return EMPTY_ROOT;
        }
        return Hashing.toHex(levels.get(levels.size() - 1).get(0));
    }

    public int size() {
        return leaves.size();
    }

    public String leafHash(int index) {
        return Hashing.toHex(leaves.get(index));
    }

    /**
     * The sibling hashes needed to walk from one leaf up to the root.
     *
     * <p>A node promoted past an odd level contributes no step, which is why a proof can be
     * shorter than the tree is deep.
     */
    public List<ProofStep> proofFor(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IndexOutOfBoundsException("no leaf at index " + leafIndex);
        }
        List<ProofStep> proof = new ArrayList<>();
        int index = leafIndex;

        for (int level = 0; level < levels.size() - 1; level++) {
            List<byte[]> nodes = levels.get(level);
            boolean isRightChild = index % 2 == 1;
            int siblingIndex = isRightChild ? index - 1 : index + 1;

            if (siblingIndex < nodes.size()) {
                proof.add(new ProofStep(
                        isRightChild ? Side.LEFT : Side.RIGHT,
                        Hashing.toHex(nodes.get(siblingIndex))));
            }
            index /= 2;
        }
        return proof;
    }

    /**
     * Verifies a proof without the tree — this is the method a third party reimplements.
     *
     * @param payload the candidate's published bytes
     */
    public static boolean verify(String payload, List<ProofStep> proof, String expectedRoot) {
        byte[] running = leafHash(payload);
        for (ProofStep step : proof) {
            byte[] sibling = Hashing.fromHex(step.hash());
            running = step.side() == Side.LEFT
                    ? internalHash(sibling, running)
                    : internalHash(running, sibling);
        }
        return Hashing.toHex(running).equals(expectedRoot);
    }

    private static List<byte[]> nextLevel(List<byte[]> nodes) {
        List<byte[]> parents = new ArrayList<>((nodes.size() + 1) / 2);
        for (int i = 0; i < nodes.size(); i += 2) {
            if (i + 1 < nodes.size()) {
                parents.add(internalHash(nodes.get(i), nodes.get(i + 1)));
            } else {
                // Promoted, not duplicated. See the class comment.
                parents.add(nodes.get(i));
            }
        }
        return parents;
    }

    private static byte[] leafHash(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        byte[] prefixed = new byte[data.length + 1];
        prefixed[0] = LEAF_PREFIX;
        System.arraycopy(data, 0, prefixed, 1, data.length);
        return Hashing.sha256(prefixed);
    }

    private static byte[] internalHash(byte[] left, byte[] right) {
        byte[] prefixed = new byte[1 + left.length + right.length];
        prefixed[0] = INTERNAL_PREFIX;
        System.arraycopy(left, 0, prefixed, 1, left.length);
        System.arraycopy(right, 0, prefixed, 1 + left.length, right.length);
        return Hashing.sha256(prefixed);
    }

    /** Which side of the pair the sibling sits on, when folding a proof back up to the root. */
    public enum Side {
        LEFT, RIGHT
    }

    /** One sibling hash on the path from a leaf to the root. */
    public record ProofStep(Side side, String hash) {
    }
}
