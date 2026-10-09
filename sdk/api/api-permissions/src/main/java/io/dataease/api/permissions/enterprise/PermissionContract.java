package io.dataease.api.permissions.enterprise;

import java.util.List;

/** W04 requests are scoped independently from legacy management DTOs. */
public final class PermissionContract {
    private PermissionContract() { }
    public interface Request { }
    public record Subject(String type, String id) { }
    public record ResourceScope(String kind, String id) { }
    public record SchoolScope(String kind, List<String> ids) { }
    public record Change(String operation, String grantId, String expectedVersion, String policyKind,
                         String resourceType, ResourceScope resourceScope, SchoolScope schoolScope,
                         String action, String effect, String status) { }
    public record CapabilityChange(String operation, String grantId, String expectedVersion,
                                   String capability, String effect, String status) { }
    public record Batch(Subject subject, String expectedEpoch, String idempotencyKey,
                        List<Change> changes) implements Request { }
    public record CapabilityBatch(Subject subject, String expectedEpoch, String idempotencyKey,
                                  List<CapabilityChange> changes) implements Request { }
    public record Catalog(Subject subject, String resourceType, String schoolId, String keyword,
                          Integer pageNum, Integer pageSize, String expectedEpoch) implements Request { }
    public record RulesPage(Subject subject, String policyKind, String resourceType, String resourceId,
                            String action, Integer pageNum, Integer pageSize, String expectedEpoch,
                            String grantId, String expectedVersion, Integer schoolsPageNum,
                            Integer schoolsPageSize) implements Request { }
    public record CapabilitiesPage(Subject subject, Integer pageNum, Integer pageSize,
                                   String expectedEpoch) implements Request { }
    public record Preview(String userId, String policyKind, String resourceType, String resourceId, String action, String expectedEpoch) implements Request { }
    public record ChangeResult(String operation, String grantId, String version) { }
    public record StoredResult(List<ChangeResult> results, String committedEpoch) { }
    public record BatchResult(List<ChangeResult> results, String committedEpoch, String currentEpoch,
                              boolean replayed) { }
}
