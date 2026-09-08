package com.zenalyst.housing.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MergeGraphTest {

    private static final Instant DAY_1 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant DAY_2 = Instant.parse("2026-03-02T10:00:00Z");
    private static final Instant DAY_3 = Instant.parse("2026-03-03T10:00:00Z");

    private static ApplicationRef ref(String applicationNo, Instant submittedAt) {
        return new ApplicationRef(UUID.randomUUID(), applicationNo, submittedAt);
    }

    @Test
    @DisplayName("applications with no matches form no groups")
    void unmatchedApplicationsAreNotGrouped() {
        MergeGraph graph = new MergeGraph();
        graph.add(ref("A-000001", DAY_1));
        graph.add(ref("A-000002", DAY_2));

        assertThat(graph.groups()).isEmpty();
    }

    @Test
    @DisplayName("the earliest submission becomes the canonical application")
    void earliestSubmissionWins() {
        ApplicationRef early = ref("A-000009", DAY_1);
        ApplicationRef late = ref("A-000002", DAY_3);

        MergeGraph graph = new MergeGraph();
        graph.add(late);
        graph.add(early);
        graph.link(late.id(), early.id());

        List<MergeGroup> groups = graph.groups();
        assertThat(groups).hasSize(1);
        // Note the canonical is A-000009 despite the higher number: submission time decides,
        // and a paper form handed in first outranks an online one recorded first.
        assertThat(groups.get(0).canonical()).isEqualTo(early);
        assertThat(groups.get(0).duplicates()).containsExactly(late);
    }

    @Test
    @DisplayName("simultaneous submissions are broken by the lower application number")
    void tiesBreakOnApplicationNumber() {
        ApplicationRef lower = ref("A-000004", DAY_1);
        ApplicationRef higher = ref("A-000007", DAY_1);

        MergeGraph graph = new MergeGraph();
        graph.add(higher);
        graph.add(lower);
        graph.link(higher.id(), lower.id());

        assertThat(graph.groups().get(0).canonical()).isEqualTo(lower);
    }

    @Test
    @DisplayName("identity is transitive: A~B and B~C makes one person, not two pairs")
    void transitiveMatchesCollapseIntoOneGroup() {
        ApplicationRef a = ref("A-000001", DAY_1);
        ApplicationRef b = ref("A-000002", DAY_2);
        ApplicationRef c = ref("A-000003", DAY_3);

        MergeGraph graph = new MergeGraph();
        graph.add(a);
        graph.add(b);
        graph.add(c);
        // A and C share nothing directly — only their common link to B.
        graph.link(a.id(), b.id());
        graph.link(b.id(), c.id());

        List<MergeGroup> groups = graph.groups();

        // The failure this guards against: two groups, {A,B} and {B,C}, leaving one person
        // holding two entries in a draw that allows one per household.
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).size()).isEqualTo(3);
        assertThat(groups.get(0).canonical()).isEqualTo(a);
        assertThat(groups.get(0).duplicates()).containsExactly(b, c);
    }

    @Test
    @DisplayName("the order matches arrive in does not change the result")
    void resultIsIndependentOfMatchOrder() {
        ApplicationRef a = ref("A-000001", DAY_1);
        ApplicationRef b = ref("A-000002", DAY_2);
        ApplicationRef c = ref("A-000003", DAY_3);

        MergeGraph forwards = new MergeGraph();
        forwards.add(a);
        forwards.add(b);
        forwards.add(c);
        forwards.link(a.id(), b.id());
        forwards.link(b.id(), c.id());

        MergeGraph backwards = new MergeGraph();
        backwards.add(c);
        backwards.add(b);
        backwards.add(a);
        backwards.link(c.id(), b.id());
        backwards.link(b.id(), a.id());

        // Deduplication is re-run as applications arrive. If the answer depended on the order
        // matches happened to be found in, two runs over the same register could disagree about
        // who the applicant is.
        assertThat(forwards.groups()).isEqualTo(backwards.groups());
    }

    @Test
    @DisplayName("linking the same pair twice changes nothing")
    void repeatedLinksAreIdempotent() {
        ApplicationRef a = ref("A-000001", DAY_1);
        ApplicationRef b = ref("A-000002", DAY_2);

        MergeGraph graph = new MergeGraph();
        graph.add(a);
        graph.add(b);
        graph.link(a.id(), b.id());
        graph.link(a.id(), b.id());
        graph.link(b.id(), a.id());

        assertThat(graph.groups()).hasSize(1);
        assertThat(graph.groups().get(0).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("separate people stay separate")
    void distinctGroupsRemainDistinct() {
        ApplicationRef a = ref("A-000001", DAY_1);
        ApplicationRef b = ref("A-000002", DAY_1);
        ApplicationRef c = ref("A-000003", DAY_2);
        ApplicationRef d = ref("A-000004", DAY_2);

        MergeGraph graph = new MergeGraph();
        List.of(a, b, c, d).forEach(graph::add);
        graph.link(a.id(), b.id());
        graph.link(c.id(), d.id());

        assertThat(graph.groups()).hasSize(2);
        assertThat(graph.groups().get(0).canonical()).isEqualTo(a);
        assertThat(graph.groups().get(1).canonical()).isEqualTo(c);
    }

    @Test
    @DisplayName("a match naming an unknown application fails loudly")
    void unknownApplicationIsRejected() {
        ApplicationRef a = ref("A-000001", DAY_1);
        MergeGraph graph = new MergeGraph();
        graph.add(a);

        // Silently ignoring it would drop a real duplicate and leave no sign it had happened.
        assertThatThrownBy(() -> graph.link(a.id(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the graph");
    }

    @Test
    @DisplayName("a long chain of matches still resolves to one person")
    void longChainsCollapse() {
        MergeGraph graph = new MergeGraph();
        List<ApplicationRef> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ApplicationRef ref = ref("A-%06d".formatted(i), DAY_1.plusSeconds(i));
            chain.add(ref);
            graph.add(ref);
        }
        for (int i = 1; i < chain.size(); i++) {
            graph.link(chain.get(i - 1).id(), chain.get(i).id());
        }

        assertThat(graph.groups()).hasSize(1);
        assertThat(graph.groups().get(0).size()).isEqualTo(50);
        assertThat(graph.groups().get(0).canonical()).isEqualTo(chain.get(0));
    }
}
