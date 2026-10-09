package io.dataease.enterprise.permission.manage;

import io.dataease.api.permissions.enterprise.PermissionContract.Preview;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.EnterpriseTenant;
import io.dataease.enterprise.management.manage.ManagementTransactions;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.permission.domain.PermissionDecision;
import jakarta.persistence.EntityManager;
import java.util.*;

/** Preview is a management operation; the target is never installed as request identity. */
public final class PermissionDecisionService {
    private final ManagementTransactions transactions;
    private final PermissionFactLoader loader;
    private final PermissionDecision decision;
    public PermissionDecisionService(ManagementTransactions transactions, PermissionFactLoader loader, PermissionDecision decision) {
        this.transactions = transactions; this.loader = loader; this.decision = decision;
    }
    public PermissionDecision.Result evaluate(EntityManager em, EnterpriseTenant tenant, long user, String type, long resource, String action) {
        if (user != io.dataease.enterprise.context.AccessContextHolder.requireCurrent().userId()) throw ManagementFields.invalid();
        return decision.evaluate(loader.loadForCurrentUser(em, tenant, type, resource), action);
    }
    public Map<String, Object> preview(Principal principal, Preview input) {
        if (input == null) throw ManagementFields.invalid();
        long user = ManagementFields.id(input.userId()), resource = ManagementFields.id(input.resourceId());
        PermissionCommands.one(input.resourceType(), PermissionCommands.TYPES);
        PermissionCommands.one(input.action(), PermissionCommands.ACTIONS);
        String policy = "DATASET".equals(input.resourceType()) ? "DATA_ACCESS" : "RESOURCE_ACTION";
        if (!policy.equals(input.policyKind()) || "DATASET".equals(input.resourceType()) && "EDIT".equals(input.action())) throw ManagementFields.invalid();
        if (input.expectedEpoch() != null) ManagementFields.id(input.expectedEpoch());
        return transactions.group(principal, "MANAGE_AUTHORIZATION", false, (em, tenant) -> {
            if (input.expectedEpoch() != null && tenant.getAccessEpoch() != ManagementFields.id(input.expectedEpoch())) throw PermissionReferences.conflict();
            var facts = loader.load(em, tenant, user, input.resourceType(), resource);
            var result = decision.evaluate(facts, input.action());
            return Map.ofEntries(Map.entry("tenantId", Long.toString(facts.tenantId())), Map.entry("userId", Long.toString(facts.userId())),
                    Map.entry("accessEpoch", Long.toString(facts.accessEpoch())), Map.entry("identityEpoch", Long.toString(facts.identityEpoch())),
                    Map.entry("resourceId", Long.toString(facts.resourceId())), Map.entry("resourceVersion", Long.toString(facts.resourceVersion())),
                    Map.entry("resourceType", input.resourceType()), Map.entry("action", input.action()), Map.entry("policyKind", policy),
                    Map.entry("platformViewQualified", facts.platformView()), Map.entry("authorizationAllowed", result.authorizationAllowed()), Map.entry("reason", result.reason()),
                    Map.entry("allowedSchoolIds", result.allowedSchoolIds()), Map.entry("sources", result.sources()),
                    Map.entry("executionReady", false), Map.entry("pendingChecks", List.of("DATASET".equals(input.resourceType()) ? "W05_SOURCE_AND_SCHOOL_FIELD_BINDING" : "CONTROLLED_PAYLOAD_OR_W06_RESOURCE_EXECUTION")));
        });
    }
}
