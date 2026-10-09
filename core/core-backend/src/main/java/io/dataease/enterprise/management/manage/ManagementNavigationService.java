package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.EnterpriseTenant;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.result.PageResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read projection of existing qualifications; never creates members or grants. */
public final class ManagementNavigationService {
    private final ManagementTransactions transactions;
    private final ManagementAuthority authority;

    public ManagementNavigationService(ManagementTransactions transactions, ManagementAuthority authority) {
        this.transactions = transactions;
        this.authority = authority;
    }

    public PageResult<Map<String, Object>> tenants(Principal principal, int page, int size) {
        return transactions.identity(principal, em -> {
            boolean platform = ManagementSessionService.qualified(em, principal.userId(), "PLATFORM_OPERATE")
                    || ManagementSessionService.qualified(em, principal.userId(), "GROUP_READ_ALL");
            String predicate = "where t.status=:active" + (platform ? "" : " and exists "
                    + "(select m.id from EnterpriseTenantMember m where m.tenantId=t.id and m.userId=:user and m.status=:active)");
            var rows = em.createQuery("from EnterpriseTenant t " + predicate + " order by t.id", EnterpriseTenant.class)
                    .setParameter("active", FoundationStatus.ACTIVE);
            var count = em.createQuery("select count(t) from EnterpriseTenant t " + predicate, Long.class)
                    .setParameter("active", FoundationStatus.ACTIVE);
            if (!platform) { rows.setParameter("user", principal.userId()); count.setParameter("user", principal.userId()); }
            var records = rows.setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream()
                    .map(t -> Map.<String, Object>of("id", t.getId().toString(), "version", t.getVersion().toString(),
                            "code", t.getCode(), "name", t.getName(), "status", t.getStatus().name())).toList();
            return new PageResult<>(records, count.getSingleResult(), PageRequest.of(page - 1, size));
        });
    }

    public Map<String, Object> navigation(Principal principal) {
        return transactions.identity(principal, em -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", Long.toString(principal.sessionId())); result.put("version", Long.toString(principal.version()));
            result.put("userId", Long.toString(principal.userId()));
            result.put("tenantId", principal.tenantId() == null ? null : principal.tenantId().toString());
            result.put("mustReset", principal.mustReset());
            result.put("platformCapabilities", List.of("PLATFORM_OPERATE", "GROUP_READ_ALL").stream()
                    .filter(c -> ManagementSessionService.qualified(em, principal.userId(), c)).toList());
            if (principal.tenantId() == null) {
                result.put("accessEpoch", null); result.put("groupCapabilities", List.of());
            } else {
                // Serialize with the same tenant lock used by management mutations before projecting capabilities.
                var tenant = em.find(EnterpriseTenant.class, principal.tenantId());
                if (tenant == null) throw new io.dataease.exception.DEException(io.dataease.result.ResultCode.PERMISSION_NO_ACCESS.code(), io.dataease.result.ResultCode.PERMISSION_NO_ACCESS.message());
                em.refresh(tenant, LockModeType.PESSIMISTIC_READ);
                if (tenant.getStatus() != FoundationStatus.ACTIVE) throw new io.dataease.exception.DEException(io.dataease.result.ResultCode.PERMISSION_NO_ACCESS.code(), io.dataease.result.ResultCode.PERMISSION_NO_ACCESS.message());
                result.put("accessEpoch", tenant.getAccessEpoch().toString());
                result.put("groupCapabilities", authority.effectiveCapabilities(em, tenant.getId(), principal.userId()).stream().sorted().toList());
            }
            return result;
        });
    }
}
