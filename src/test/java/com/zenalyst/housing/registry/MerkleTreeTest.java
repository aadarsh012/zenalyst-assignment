package com.zenalyst.housing.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MerkleTreeTest {

    private static List<String> payloads(int count) {
        List<String> payloads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            payloads.add("{\"applicationNo\":\"A-%06d\"}".formatted(i));
        }
        return payloads;
    }

    @Test
    @DisplayName("the same rows always produce the same root")
    void rootIsDeterministic() {
        assertThat(MerkleTree.of(payloads(7)).root())
                .isEqualTo(MerkleTree.of(payloads(7)).root())
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("changing one row changes the root")
    void anyChangeChangesTheRoot() {
        List<String> original = payloads(7);
        List<String> tampered = new ArrayList<>(original);
        tampered.set(3, "{\"applicationNo\":\"A-999999\"}");

        assertThat(MerkleTree.of(tampered).root()).isNotEqualTo(MerkleTree.of(original).root());
    }

    @Test
    @DisplayName("adding or removing a row changes the root")
    void membershipIsCommittedTo() {
        String seven = MerkleTree.of(payloads(7)).root();

        assertThat(MerkleTree.of(payloads(8)).root()).isNotEqualTo(seven);
        assertThat(MerkleTree.of(payloads(6)).root()).isNotEqualTo(seven);
    }

    @Test
    @DisplayName("reordering rows changes the root — order is part of the commitment")
    void orderIsCommittedTo() {
        List<String> original = payloads(5);
        List<String> swapped = new ArrayList<>(original);
        swapped.set(0, original.get(1));
        swapped.set(1, original.get(0));

        assertThat(MerkleTree.of(swapped).root()).isNotEqualTo(MerkleTree.of(original).root());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 100, 4000})
    @DisplayName("every leaf in a tree of any size proves its own inclusion")
    void everyLeafProvesInclusion(int size) {
        List<String> rows = payloads(size);
        MerkleTree tree = MerkleTree.of(rows);
        String root = tree.root();

        for (int index = 0; index < size; index++) {
            assertThat(MerkleTree.verify(rows.get(index), tree.proofFor(index), root))
                    .as("leaf %d of %d", index, size)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a proof does not work for a row that was not in the tree")
    void proofsDoNotTransferToOtherData() {
        List<String> rows = payloads(9);
        MerkleTree tree = MerkleTree.of(rows);

        // Somebody who was never a candidate cannot borrow a candidate's proof.
        assertThat(MerkleTree.verify("{\"applicationNo\":\"A-999999\"}", tree.proofFor(4), tree.root()))
                .isFalse();
    }

    @Test
    @DisplayName("a proof does not work against a different root")
    void proofsDoNotTransferToOtherTrees() {
        MerkleTree tree = MerkleTree.of(payloads(9));
        MerkleTree other = MerkleTree.of(payloads(10));

        assertThat(MerkleTree.verify(payloads(9).get(4), tree.proofFor(4), other.root())).isFalse();
    }

    @Test
    @DisplayName("a tampered proof step fails")
    void tamperedProofsFail() {
        List<String> rows = payloads(8);
        MerkleTree tree = MerkleTree.of(rows);

        List<MerkleTree.ProofStep> tampered = new ArrayList<>(tree.proofFor(2));
        MerkleTree.ProofStep first = tampered.get(0);
        tampered.set(0, new MerkleTree.ProofStep(first.side(), "f".repeat(64)));

        assertThat(MerkleTree.verify(rows.get(2), tampered, tree.root())).isFalse();
    }

    @Test
    @DisplayName("a proof step on the wrong side fails")
    void proofSidesMatter() {
        List<String> rows = payloads(8);
        MerkleTree tree = MerkleTree.of(rows);

        // Concatenation order is part of the hash, so a proof cannot be reused mirrored.
        List<MerkleTree.ProofStep> flipped = tree.proofFor(2).stream()
                .map(step -> new MerkleTree.ProofStep(
                        step.side() == MerkleTree.Side.LEFT ? MerkleTree.Side.RIGHT : MerkleTree.Side.LEFT,
                        step.hash()))
                .toList();

        assertThat(MerkleTree.verify(rows.get(2), flipped, tree.root())).isFalse();
    }

    @Test
    @DisplayName("an odd node is promoted, not duplicated")
    void oddNodesArePromotedNotDuplicated() {
        // Duplicating the last node when a level is odd is the common implementation and carries
        // Bitcoin's CVE-2012-2459 flaw: a three-leaf tree [a,b,c] and a four-leaf tree [a,b,c,c]
        // collapse to the same root, so a set of candidates would no longer be uniquely committed
        // to. Promotion keeps them distinct.
        List<String> three = payloads(3);
        List<String> threeWithLastRepeated = new ArrayList<>(three);
        threeWithLastRepeated.add(three.get(2));

        assertThat(MerkleTree.of(three).root())
                .isNotEqualTo(MerkleTree.of(threeWithLastRepeated).root());
    }

    @Test
    @DisplayName("a leaf cannot masquerade as an internal node")
    void leavesAndInternalNodesAreDomainSeparated() {
        // RFC 6962 prefixes leaves with 0x00 and internal nodes with 0x01. Without that, a leaf
        // whose bytes happened to be two concatenated digests would hash identically to the node
        // above them, letting a tree of a different shape pass as the original.
        MerkleTree two = MerkleTree.of(payloads(2));
        String forgedLeaf = two.leafHash(0) + two.leafHash(1);

        assertThat(MerkleTree.of(List.of(forgedLeaf)).root()).isNotEqualTo(two.root());
    }

    @Test
    @DisplayName("an empty register still has a stated root")
    void emptyTreeHasARoot() {
        assertThat(MerkleTree.of(List.of()).root())
                .isEqualTo(MerkleTree.EMPTY_ROOT)
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("a single row is its own tree")
    void singleLeafTree() {
        MerkleTree tree = MerkleTree.of(payloads(1));

        assertThat(tree.proofFor(0)).isEmpty();
        assertThat(MerkleTree.verify(payloads(1).get(0), List.of(), tree.root())).isTrue();
    }

    @Test
    @DisplayName("proofs stay small as the register grows")
    void proofSizeIsLogarithmic() {
        // 4,000 candidates: twelve hashes, about 800 bytes. An applicant can be handed their
        // proof on a printed page.
        MerkleTree tree = MerkleTree.of(payloads(4000));
        assertThat(tree.proofFor(1999)).hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void rejectsProofForNonexistentLeaf() {
        MerkleTree tree = MerkleTree.of(payloads(3));
        assertThatThrownBy(() -> tree.proofFor(3)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
