package io.dataease.enterprise.tenant.domain;

import io.dataease.exception.DEException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.dataease.enterprise.tenant.domain.OrganizationNode.Kind.*;
import static io.dataease.enterprise.tenant.domain.OrganizationNode.Status.*;
import static org.assertj.core.api.Assertions.*;

class OrganizationHierarchyTest {
    private final OrganizationHierarchy hierarchy = new OrganizationHierarchy(128);

    private static OrganizationNode node(long id, Long parent, Long school) {
        return new OrganizationNode(id, 10, parent, school == null && id == 1 ? SCHOOL : DEPARTMENT,
                "合成组织", id == 1 ? "0007" : null, school, ACTIVE, 1);
    }

    private static void invalid(Runnable operation, int code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DEException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    @Test
    void unicodeWhitespaceControlsAndMalformedSurrogatesAreRejectedWithoutNormalization() {
        for (String value : new String[]{"", " x", "x\u00a0", "\u3000x", "x\nx", "x\u0000", "\ud800"}) {
            invalid(() -> OrganizationHierarchy.requireText(value, 64), 10001);
        }
        invalid(() -> OrganizationHierarchy.requireText(null, 64), 10001);
        OrganizationHierarchy.requireText("x y🙂", 64);
    }

    @Test
    void textLimitsCountUnicodeCodePointsAndPreserveSchoolCode() {
        OrganizationHierarchy.requireText("🙂".repeat(64), 64);
        invalid(() -> OrganizationHierarchy.requireText("🙂".repeat(65), 64), 10001);
        var school = node(1, null, null);
        hierarchy.validate(school, ignored -> null);
        assertThat(school.schoolCode()).isEqualTo("0007");
        OrganizationHierarchy.requireText("School-A", 64);
    }

    @Test
    void invalidKindReferenceCombinationsAreRejected() {
        invalid(() -> hierarchy.validate(new OrganizationNode(1, 10, null, SCHOOL, "校", "7", 2L, ACTIVE, 1), ignored -> null), 10001);
        invalid(() -> hierarchy.validate(new OrganizationNode(2, 10, null, DEPARTMENT, "部", "7", null, ACTIVE, 1), ignored -> null), 10001);
        invalid(() -> hierarchy.validate(new OrganizationNode(2, 0, null, DEPARTMENT, "部", null, null, ACTIVE, 1), ignored -> null), 10001);
    }

    @Test
    void selfAndExistingParentCyclesAreRejected() {
        invalid(() -> hierarchy.validate(node(2, 2L, null), ignored -> null), 10001);
        var nodes = Map.of(2L, node(2, 3L, null), 3L, node(3, 2L, null));
        invalid(() -> hierarchy.validate(node(4, 2L, null), nodes::get), 10001);
    }

    @Test
    void schoolScopesRemainPairedAcrossTheWholeParentChain() {
        var groupDepartment = node(4, null, null);
        var school = node(1, 4L, null);
        var department = node(2, 1L, 1L);
        var nodes = Map.of(1L, school, 2L, department, 4L, groupDepartment);
        hierarchy.requireAvailable(node(3, 2L, 1L), nodes::get);
        invalid(() -> hierarchy.validate(node(3, 2L, null), nodes::get), 10001);
        invalid(() -> hierarchy.validate(node(1, 2L, null), nodes::get), 10001);
        // A school-owned department may be a root, with the authoritative school reference retained.
        hierarchy.requireAvailable(node(3, null, 1L), nodes::get);
    }

    @Test
    void missingForeignOrWrongKindSchoolReferencesAreRejected() {
        invalid(() -> hierarchy.validate(node(2, null, 1L), ignored -> null), 70002);
        invalid(() -> hierarchy.validate(node(2, null, 1L), ignored -> new OrganizationNode(1, 20, null, SCHOOL, "校", "8", null, ACTIVE, 1)), 70002);
        invalid(() -> hierarchy.validate(node(2, null, 3L), ignored -> node(3, null, null)), 10001);
        invalid(() -> hierarchy.validate(node(2, 4L, null), ignored -> node(5, null, null)), 70002);
    }

    @Test
    void inactiveAncestorOrReferencedSchoolMakesOrganizationUnavailable() {
        var disabled = new OrganizationNode(1, 10, null, SCHOOL, "校", "0007", null, DISABLED, 1);
        hierarchy.validate(node(2, null, 1L), ignored -> disabled);
        invalid(() -> hierarchy.requireAvailable(node(2, null, 1L), ignored -> disabled), 70001);
        var ancestor = new OrganizationNode(4, 10, null, DEPARTMENT, "部", null, null, DISABLED, 1);
        invalid(() -> hierarchy.requireAvailable(node(2, 4L, null), ignored -> ancestor), 70001);
    }

    @Test
    void traversalBudgetRejectsInsteadOfAcceptingATruncatedPath() {
        var limited = new OrganizationHierarchy(2);
        var nodes = Map.of(3L, node(3, 4L, null), 4L, node(4, null, null));
        invalid(() -> limited.validate(node(2, 3L, null), nodes::get), 10001);
        limited.validate(node(3, 4L, null), nodes::get);
        assertThatThrownBy(() -> new OrganizationHierarchy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
