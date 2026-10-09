#!/usr/bin/env python3
"""Only the dedicated W02 environment. --private stdout must go to a private pipe."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
SOURCE = ROOT / 'source'
TOOLS = SOURCE / 'tools/phase1'
JAR = SOURCE / 'core/core-backend/target/CoreApplication.jar'
TOOL_NAMES = ['login-test-context.py', 'browser-login-regression.cjs',
              'verify-delivery.ps1', 'pre-push-check.sh', 'test-delivery-gate.py',
              'remote-json-response.ps1', 'test-remote-json-response.ps1',
              'login-startup-regression.cjs', 'community-compatibility.cjs',
              'verify-database-boundary.py', 'verify-foundation.py', 'verify-w03-control.py', 'verify-w04-roles.py', 'verify-w04-storage.py', 'resource-job.py', 'verify-resource-job.py', 'build-safe.py', 'verify-w04-permissions.py', 'verify-w04-decisions.py']
PROTOCOL_CASES = {'protocol.' + name for name in ['object-single', 'object-multiline',
                  'multiple-objects', 'array-root', 'scalar-root', 'malformed-json']}


def protocol_sources():
    return {name: digest((TOOLS / name).read_bytes()) for name in
            ['remote-json-response.ps1', 'test-remote-json-response.ps1']}


UNIT_SUITES = {'EnterpriseAssemblyGuardTest': 12, 'AccessContextHolderTest': 10,
               'FoundationConfigurationTest': 6, 'FoundationMigrationTest': 18,
               'EnterpriseJpaIsolationTest': 14, 'FoundationJpaMappingTest': 12,
               'OrganizationHierarchyTest': 8, 'OrganizationTransactionTest': 12,
               'EnterpriseMigrationEvolutionTest': 12, 'AuditMigrationTest': 8, 'OrganizationAuditTest': 8}
AUDIT_REGRESSIONS = {'AuditMigrationTest.' + name for name in [
    'formalPlanCreatesFiveEmptyTablesAndRestartsWithoutNewRecords',
    'formalUpgradePreservesV41RowsAndDoesNotReexecuteSuccessfulMigration',
    'committedAuditDdlFailureRetriesAndRetainsFailureRecord',
    'existingAuditDriftFailsWithoutRepairOrChangingRows',
    'nullableScopeAndActorBranchesRejectIncompleteRows',
    'jsonMustBeObjectAndWithinBoundedStorage',
    'auditForeignKeysRejectUnknownTenantAndActor',
    'dictionaryShapeHasThirteenColumnsTwoForeignKeysAndImmutableChecks'
]} | {'OrganizationAuditTest.' + name for name in [
    'organizationCreateAndUpdateCommitWithFormalAudit',
    'auditFailureAfterInsertRollsBackCreateAndEpoch',
    'auditFailureAfterInsertRollsBackUpdateAndEpoch',
    'invalidTraceAndOperationCannotLeaveAuditOrOrganization',
    'forgedChangeScopeActorAndEpochRejects',
    'missingContextUnboundTransactionAndWrongFactoryReject',
    'twoGroupsKeepTheirOrganizationAuditSeparated',
    'mappingIsImmutableAndRemovalIsRejected'
]}
EVOLUTION_REGRESSIONS = {'EnterpriseMigrationEvolutionTest.' + name for name in [
    'prefixSimilarVersionGroupCannotSuppressFoundationMigration',
    'unsupportedNewerHistoryRejectsBeforeDdlVersionWritesAndCommunityBlocks',
    'frozenV41DdlHashesAndNestedDescriptorsAreImmutable',
    'duplicateNonCanonicalWrongGroupAndGappedPlansReject',
    'unknownSkippedRegressedDuplicateAndIncompleteHistoriesReject',
    'repeatedFailuresThenSuccessAndNextFailureRemainRetryable',
    'otherGroupsKeepTheirExistingHistorySemantics',
    'currentTargetSnapshotsCannotMutateFrozenHistory',
    'realListenerEmptyUpgradeAndRepeatedStartupKeepCurrentTargetAndRows',
    'partialSecondMigrationRetainsCommittedDdlAndRetriesWithoutV41',
    'currentVerifierRejectsEvolvedDriftWithoutChangingHistoryOrRows',
    'invalidPlansAndUnresolvedFailuresPreventActualVersionWrites'
]}
ORGANIZATION_REGRESSIONS = {'OrganizationHierarchyTest.' + name for name in [
    'unicodeWhitespaceControlsAndMalformedSurrogatesAreRejectedWithoutNormalization',
    'textLimitsCountUnicodeCodePointsAndPreserveSchoolCode',
    'invalidKindReferenceCombinationsAreRejected',
    'selfAndExistingParentCyclesAreRejected',
    'schoolScopesRemainPairedAcrossTheWholeParentChain',
    'missingForeignOrWrongKindSchoolReferencesAreRejected',
    'inactiveAncestorOrReferencedSchoolMakesOrganizationUnavailable',
    'traversalBudgetRejectsInsteadOfAcceptingATruncatedPath'
]} | {'OrganizationTransactionTest.' + name for name in [
    'createsSchoolWithAssignedIdExactCodeAndAtomicEpochAudit',
    'explicitParentClearUsesCasAndReloadsWithoutLosingImmutableFields',
    'staleVersionAndEpochRejectWithoutAnyPartialWrites',
    'missingContextAndMissingCollaboratorsNeverFallBackToCommunity',
    'foreignGroupOrganizationParentAndSchoolRejectInBothDirections',
    'exactSchoolLookupRejectsUnknownForeignAndInvalidCodes',
    'inactiveParentsSchoolsUsersAndTenantsRejectCurrentLookupsOrCommands',
    'managementAndMembershipAreRequiredEvenWithValidContext',
    'auditFailureRollsBackOrganizationVersionEpochAndAuditRowTogether',
    'concurrentMutualReparentingCannotCommitCycleAndRetryChecksFreshTree',
    'activeOrganizationCannotReferenceDepartmentAsSchoolOrMoveAcrossSchoolScope',
    'ambientTransactionAndStalePersistenceContextCannotBypassChecks'
]}
MAPPING_REGRESSIONS = {'FoundationJpaMappingTest.' + name for name in [
    'defaultAutomaticScanExcludesEnterpriseAndKeepsCommunity',
    'explicitFalseAutomaticScanExcludesEnterprise',
    'enabledScanMapsExactlyFourTablesAndAllFortyThreeColumnsWithoutDdl',
    'enabledMappingNeverCreatesMissingFoundationTables',
    'fourProductionEntitiesRoundTripAssignedIdsAuditEnumsUnicodeAndSchoolCode',
    'optimisticVersionRejectsStaleUpdatesForAllFourEntities',
    'managedExplicitNullClearsOptionalFieldsAndReloadsOutsideContext',
    'multiTableStateAndEpochRollbackTogetherAfterFlush',
    'crossGroupParentAndSchoolReferencesRejectInBothDirections',
    'disabledSchoolKeepsGloballyReservedSchoolCode',
    'globalUserCanHaveTwoGroupMembersButNotDuplicateWithinGroup',
    'immutableOwnershipAndNaturalKeysRemainUnchangedDuringOrdinaryJpaUpdate'
]}
JPA_REGRESSIONS = {'EnterpriseJpaIsolationTest.' + name for name in [
    'schemaOwnershipRegisteredEvenWhenFoundationSwitchIsAbsent',
    'existingProvidersAreRejectedWithoutOverwritingThem',
    'allSchemaOperationsExcludeReservedTableNames',
    'unprotectedHibernateActuallyCreatesEnterpriseTable',
    'missingEnterpriseTableStaysAbsentWhileCommunityTableIsCreated',
    'existingEnterpriseStructureAndRowsSurviveCommunityColumnUpdate',
    'driftRemainsUnrepairedAndStrictVerifierRejectsIt',
    'enterpriseMappingsRequireFoundationBeforeAnyDdl',
    'crossBoundaryForeignKeyIsRejectedBeforeAnyDdl',
    'mixedSecondaryTableIsRejectedBeforeAnyDdl',
    'generatedEnterpriseIdIsRejectedBeforeAnyDdl',
    'explicitEnterpriseCatalogIsRejectedBeforeAnyDdl',
    'replacedSchemaFilterIsRejectedBeforeAnyDdl',
    'migratedTableSupportsJpaReadAndTransactionalUpdate'
]}
SCHEMA_REGRESSIONS = {
    'FoundationConfigurationTest.defaultsPreserveLiteralContentsAndOnlyNormalizeKnownFunctionCase',
    'FoundationMigrationTest.literalDefaultCaseDriftFailsBeforeCreatingOtherTables',
    'FoundationMigrationTest.startupVerifierRejectsLiteralDefaultCaseDriftInEveryTable',
    'FoundationMigrationTest.prefixIndexDriftFailsBeforeCreatingOtherTables',
    'FoundationMigrationTest.startupVerifierRejectsPrefixUniqueSchoolCodeIndexWithoutRepair',
    'FoundationMigrationTest.startupVerifierRejectsDescendingOrAdditionalFunctionalIndexParts',
    'FoundationConfigurationTest.checkNormalizerPreservesGroupingAndLiteralCase',
    'FoundationMigrationTest.checkLiteralContentsCannotBeNormalizedIntoAnotherStatus'
}


UNIT_SUITES.update({'ManagementConfigurationTest': 3, 'ManagementRequestBridgeTest': 3, 'AuthorityMigrationTest': 6, 'CredentialMigrationTest': 7, 'ManagementAuthorityTest': 12, 'ManagementHttpBoundaryTest': 35, 'ManagementResponseTest': 1, 'ManagementSessionTest': 11, 'ResourceMigrationTest': 4, 'PasswordCodecTest': 3})
W03_REGRESSIONS = set(['AuthorityMigrationTest.authorityDriftRefusesBeforeCreatingRemainingTables', 'AuthorityMigrationTest.committedFirstAuthorityTableFailureRetainsLedgerAndRetries', 'AuthorityMigrationTest.formalThreeStepEmptyPlanKeepsSuccessfulHistoryOnRestart', 'AuthorityMigrationTest.sixTableDescriptorsRemainImmutableAndFullyCommented', 'AuthorityMigrationTest.unknownSubjectTypeAndInvalidCapabilityCannotGrantAuthority', 'AuthorityMigrationTest.v42RowsSurviveRealListenerUpgradeWithoutReexecutingOldSteps', 'CredentialMigrationTest.committedCredentialDdlFailureRetainsFailedLedgerAndRetries', 'CredentialMigrationTest.credentialDriftRefusesBeforeCreatingRemainingTables', 'CredentialMigrationTest.explicitInitializationIsAtomicRestrictedAndCannotRepeat', 'CredentialMigrationTest.failedInitializationRollsBackAccountAndQualifications', 'CredentialMigrationTest.formalFourStepEmptyPlanRetainsHistoryAndProvidesNoCredentials', 'CredentialMigrationTest.privateInputRejectsPermissionsDuplicateKeysAndTrailingJson', 'CredentialMigrationTest.v43RowsSurviveListenerUpgradeWithoutOldStepsRepeating', 'ManagementAuthorityTest.disabledOrganizationAncestorDoesNotConferManagement', 'ManagementAuthorityTest.emptyDisabledOrNonSchoolRoleScopeRejects', 'ManagementAuthorityTest.explicitOrganizationGrantRequiresExactActiveMembership', 'ManagementAuthorityTest.explicitPersonalDenyOverridesOrganizationAllow', 'ManagementAuthorityTest.formalV43UpgradeRetainsRowsRepeatsAndRejectsCrossGroupSubject', 'ManagementAuthorityTest.membershipAndAdministratorRoleNameDoNotGrantManagement', 'ManagementAuthorityTest.missingContextAndMissingTransactionReject', 'ManagementAuthorityTest.otherGroupAndDisabledMemberCannotUseGrants', 'ManagementAuthorityTest.personalGrantEnablesRealOrganizationAndAuditTransaction', 'ManagementAuthorityTest.roleDenyOverridesPersonalAllowWithoutCombiningSchoolSets', 'ManagementAuthorityTest.roleGrantRequiresItsOwnActiveAssignmentAndSchools', 'ManagementAuthorityTest.staleIdentityTenantRevisionAndDisabledUserReject', 'ManagementConfigurationTest.defaultModeSuppliesNoIdentityOrRequestFilter', 'ManagementConfigurationTest.invalidSwitchRejectsBeforeAssembly', 'ManagementConfigurationTest.managementWithoutFoundationAndCombinedFullModeReject', 'ManagementHttpBoundaryTest.credentialsOriginsAndLegacyRoutesCannotBypassProductionFilter', 'ManagementHttpBoundaryTest.groupResourcesAndSchoolReferencesRejectBothDirectionsWithoutMetadata', 'ManagementHttpBoundaryTest.lastAdministratorAndImmutableSchoolAttributionCannotBeRemoved', 'ManagementHttpBoundaryTest.nativeOwnershipFailureRollsBackResourceEpochAndAuditThenRetries', 'ManagementHttpBoundaryTest.nativeOwnershipRequiresSelectedGroupAndNeverGrantsEditing', 'ManagementHttpBoundaryTest.platformQualificationsRemainIndependentAndRevocationIsImmediate', 'ManagementHttpBoundaryTest.realContextSwitchChecksMemberCasAndImmediateRevocation', 'ManagementHttpBoundaryTest.realLoginRefusesDuringMigrationAndOpensOnlyAfterInitialization', 'ManagementHttpBoundaryTest.realOrganizationMemberLifecycleHasAuditCasAndImmediateRevocation', 'ManagementHttpBoundaryTest.realPasswordChangeAndExpiryInvalidateExistingCredentials', 'ManagementHttpBoundaryTest.reusedRealHttpWorkerClearsGroupContextAfterRejectedRequests', 'ManagementHttpBoundaryTest.strictJsonRejectsUnknownDuplicateTypeTrailingAndOversizedBodies', 'ManagementRequestBridgeTest.activeScopeMatchesOnlyTheExactRequestAndClearsAfterException', 'ManagementRequestBridgeTest.headersAndPathsAloneNeverBypassNativeAuthentication', 'ManagementRequestBridgeTest.nestedOrOtherThreadScopesAndNonManagementPathsReject', 'ManagementResponseTest.downstreamCacheOverridesAndResetCannotExposeCredentialsToCaches', 'ManagementSessionTest.concurrentWrongPasswordsAllCountTowardsLock', 'ManagementSessionTest.corruptStoredParametersFailClosedWithoutCreatingSession', 'ManagementSessionTest.fiveFailuresLockAtomicallyAndExpiryRestartsCounter', 'ManagementSessionTest.forcedResetCannotSwitchAndPasswordChangeRevokesEveryOldSession', 'ManagementSessionTest.idleAndAbsoluteExpiryAndLogoutDenyFurtherUse', 'ManagementSessionTest.loginPersistsOnlyDigestAndReturnsUnselectedRestrictedIdentity', 'ManagementSessionTest.malformedTokensCannotSelectAnIdentity', 'ManagementSessionTest.memberSwitchRejectsOtherGroupAndStaleRevision', 'ManagementSessionTest.memberTenantAndIdentityRevocationApplyToExistingSessions', 'ManagementSessionTest.platformAllReadSwitchesWithoutMembershipButDoesNotBecomeOperate', 'ManagementSessionTest.unknownAndDisabledIdentityUseSameLoginErrorWithoutSession', 'PasswordCodecTest.independentSaltAndCorrectComparison', 'PasswordCodecTest.invalidPasswordsAreRejectedBeforeDerivation', 'PasswordCodecTest.malformedOrUnboundedParametersAreNeverAccepted', 'ResourceMigrationTest.committedResourceDdlFailureRetainsFailedLedgerAndSafelyRetries', 'ResourceMigrationTest.exactFiveStepEmptyPlanAndRepeatProvideNoResources', 'ResourceMigrationTest.ownershipConstraintsRejectBothGroupsAndPayloadClaims', 'ResourceMigrationTest.v44DataSurvivesResourceUpgradeWithoutHistoryRewrite'])
W03_HTTP_CASES = set(['controller.view', 'formal.anonymous', 'formal.fake-group', 'formal.legacy-login', 'formal.login', 'formal.logout', 'formal.logout-replay', 'formal.correct-password-locked', 'formal.expired-session', 'role.personal-deny-overrides', 'role.no-school-cross-product', 'role.B-independent-allow', 'formal.untrusted-origin', 'formal.wrong-password', 'member.cas', 'member.create', 'member.disable', 'member.foreign-update', 'member.immediate-revocation', 'member.last-admin', 'member.list', 'member.no-implicit-management', 'organization.A-to-B', 'organization.B-to-A', 'organization.cas', 'organization.clear', 'organization.empty', 'organization.foreign-parent', 'organization.reopen', 'organization.unknown', 'platform.A-not-B', 'platform.B-not-A', 'platform.no-DRILL', 'platform.no-EDIT', 'platform.no-EXPORT', 'platform.select-A', 'platform.select-B', 'platform.view-A', 'platform.view-B', 'reader.no-platform-control', 'reader.revoked', 'resource.A-create', 'resource.A-to-B', 'resource.B-create', 'resource.B-to-A', 'resource.native-envelope-consistent', 'resource.no-rebind', 'resource.unregistered-native'])

GRANT_STORAGE_REGRESSIONS = set(["GrantStorageTest.sixStepEmptyPlanAndRepeatHaveNoDefaultBusinessGrants","GrantStorageTest.seededV45UpgradeRetainsEveryOldRowAndSuccessfulHistory","GrantStorageTest.committedGrantDdlFailureRetainsLedgerAndResumesMissingSchoolTable","GrantStorageTest.existingExpressionDriftRefusesBeforeCreatingOtherTableAndWithoutRepair","GrantStorageTest.subjectsAndTypedResourcesRejectBothCrossGroupDirections","GrantStorageTest.nullResourceNaturalKeyIsUniqueAndAllowsIndependentDeny","GrantStorageTest.invalidPolicyActionsScopesAndNullableBranchesCannotPassChecks","GrantStorageTest.schoolAssociationsRejectBothForeignDirectionsDuplicatesAndUnknownSchools","GrantStorageTest.jpaGeneratedSlotIsReadOnlyAndNaturalIdentityCannotBeReplaced","GrantStorageTest.jpaCasAndExplicitSchoolClearReloadOutsidePersistenceContext","GrantStorageTest.multiTableFlushFailureRollsBackGrantSchoolsAndTenantRevision","GrantStorageTest.unknownReservedObjectsAndOldFiveStepPlanRejectWithoutChangingHistory"])
UNIT_SUITES.update({'GrantStorageTest': 12})

IDEMPOTENCY_STORAGE_REGRESSIONS = set(["IdempotencyStorageTest.sevenStepEmptyPlanAndRepeatHaveNoRecordsOrEmbeddedAppDependency","IdempotencyStorageTest.seededV46UpgradeRetainsPolicyRowsAndSuccessfulHistory","IdempotencyStorageTest.committedIdempotencyDdlFailureRetainsLedgerAndRetriesWithoutLosingRows","IdempotencyStorageTest.generationAndDefaultDriftRemainUnrepairedAndFailedHistoryIsPreserved","IdempotencyStorageTest.userOnlyPrincipalOperationsKeysExpiryAndForeignReferencesReject","IdempotencyStorageTest.keyScopeSeparatesGroupUserOperationAndExactCaseWhileDuplicatesReject","IdempotencyStorageTest.doneMetadataRequiresBoundedJsonObjectIncludingUtf8Storage","IdempotencyStorageTest.jpaGeneratedPrincipalIsReadOnlyAndDigestHasExactDefensiveCopies","IdempotencyStorageTest.jpaOptimisticConflictAndManagedExplicitClearArePersistedCorrectly","IdempotencyStorageTest.springTransactionFailureRollsBackResultPolicyAuditAndRevisionTogether","IdempotencyStorageTest.concurrentSameKeyHasExactlyOneCommittedResult","IdempotencyStorageTest.oldSixStepPlanCannotDowngradeSuccessfulV47History"])
UNIT_SUITES.update({'IdempotencyStorageTest': 12})

GENERATED_REGRESSIONS = set(["GeneratedColumnSchemaTest.coldMigrationEntryPointsNeverReenterCurrentTarget","GeneratedColumnSchemaTest.frozenV41ThroughV45DdlAndDescriptorsRemainUnchanged","GeneratedColumnSchemaTest.storedSlotMetadataAndNullNaturalKeyAreActuallyEnforced","GeneratedColumnSchemaTest.changedGenerationExpressionFailsWithoutRepair","GeneratedColumnSchemaTest.virtualAndWritableReplacementBothFailWithoutRepair","GeneratedColumnSchemaTest.literalCaseWhitespaceAndIntroducerTextRemainDistinct","GeneratedColumnSchemaTest.ordinaryColumnCannotMasqueradeAsGeneratedColumn","GeneratedColumnSchemaTest.generatedTypeCommentAndIndexDriftRemainRejected","GeneratedColumnSchemaTest.explicitlyWritingGeneratedSlotIsRejectedByMySql","GeneratedColumnSchemaTest.generatedDescriptorRejectsInventedNonNullOrDefaultContract"])
UNIT_SUITES.update({'GeneratedColumnSchemaTest': 10})

STORAGE_CASES = GENERATED_REGRESSIONS | GRANT_STORAGE_REGRESSIONS | IDEMPOTENCY_STORAGE_REGRESSIONS | {'storage.port'} | {
    'storage.' + label + '.' + kind for label in ['compatibility', 'management']
    for kind in ['tables', 'columns', 'generated', 'history']
} | {'storage.compatibility.empty', 'storage.management.preserved'}

RESOURCE_CASES = {'resource.limits', 'resource.mutual-exclusion', 'resource.capacity-refusal',
                  'resource.timeout-descendants', 'resource.cancel-descendants',
                  'resource.oom-contained', 'resource.protected-services'}

W04_REGRESSIONS = set(["ManagementHttpBoundaryTest.w04RoleLifecycleStrictCasAndBothGroupDirections","ManagementHttpBoundaryTest.w04AssignmentPairsReplaceClearRejectCrossGroupAndImmutableRoot","ManagementHttpBoundaryTest.w04DelegatedMemberAndRoleMutationsCannotConferManagement","ManagementHttpBoundaryTest.w04OrganizationDenyRemovalAndLastRoleAdministratorRollBack"])
W04_HTTP_CASES = set(["w04.operator-login","w04.delegate-member","w04.delegate-select","w04.member-indirect-escalation","w04.role-read","w04.role-other-group-read","w04.role-cross-update","w04.role-reverse-cross-update","w04.role-duplicate","w04.second-school","w04.assignment-create","w04.financial-assignment","w04.other-assignment-create","w04.assignment-cross-update","w04.assignment-reverse-cross-update","w04.assignment-reverse-foreign-school","w04.assignment-foreign-member","w04.assignment-reverse-foreign-member","w04.assignment-foreign-role-filter","w04.assignment-reverse-foreign-role-filter","w04.assignment-reverse-foreign-school-filter","w04.assignment-pairs","w04.assignment-duplicate","w04.foreign-school","w04.foreign-role","w04.active-empty","w04.duplicate-school","w04.foreign-page-filter","w04.assignment-update","w04.assignment-cas","w04.assignment-immutable-role","w04.assignment-disable-clear","w04.assignment-read-disabled","w04.delegate-ordinary-role","w04.assignment-indirect-escalation","w04.numeric-role-id","w04.null-page","w04.unknown-role-field","w04.role-create-null-id","w04.old-business-route","w04.assignment-unknown","w04.role-disable","w04.role-cas","w04.disabled-role-active-assignment","w04.operator-no-group","w04.anonymous-role","w04.delegate-still-valid","w04.self-disable-last-admin"])

def require(value, code):
    if not value:
        raise RuntimeError(code)


W04_REGRESSIONS.update({'ManagementHttpBoundaryTest.w04PermissionNestedJsonRejectsAmbiguityAndBounds', 'ManagementHttpBoundaryTest.w04PermissionResourceCatalogRequiresNativeOwnershipAndNeverReturnsData', 'ManagementHttpBoundaryTest.w04PermissionThreeSubjectsReadWithoutWritingAndCreateOrganizationAtomically', 'ManagementHttpBoundaryTest.w04PermissionBothGroupDirectionsAndMixedBatchAreRejected', 'ManagementHttpBoundaryTest.w04PermissionSchoolsPaginationBindsEpochAndRuleRevision', 'ManagementHttpBoundaryTest.w04PermissionConcurrentSameKeyCommitsOnceAndRetryRechecksAuthority', 'ManagementHttpBoundaryTest.w04PermissionDatabaseFailureRollsBackSubjectChildrenEpochAuditIdempotency', 'ManagementHttpBoundaryTest.w04CapabilityBatchProtectsLastAdministratorAndSeparatesManagementFromData', 'ManagementHttpBoundaryTest.w04PermissionCasFullReplacementAndDeleteCreateNaturalKey', 'ManagementHttpBoundaryTest.w04PermissionIdempotencyReplaysOldEpochRejectsChangedExpiredAndRevoked'})
PERMISSION_HTTP_CASES = {'pc.rule-replaced-state', 'pc.runtime-unchanged', 'pc.capabilities-last-admin', 'pc.operator-login', 'pc.capabilities-platform-reject', 'pc.operator-no-group', 'pc.capabilities-remove-old', 'pc.json-duplicate', 'pc.refusals-do-not-write', 'pc.capabilities-last-admin-rollback', 'pc.same-user-return-switch', 'pc.unavailable-department-reject', 'pc.mixed-atomic-refusal', 'pcA.select', 'pc.capabilities-replay', 'pc.school-cross-reverse', 'pc.capabilities-own-last-denied', 'pc.resource-create', 'pcB.reset', 'pc.json-unknown', 'pcB.login', 'pc.resource-action-write', 'pc.same-user-other-member', 'pc.json-numeric', 'pc.same-user-other-key', 'pc.capabilities-own-last-rollback', 'pcDelegate.login', 'pc.catalog-scoped-metadata', 'pc.subject-cross-reverse', 'pc.linked-school-disable', 'pc.ordinary-no-configuration', 'pc.dataset-drill', 'pc.replay-no-write', 'pc.school-page-stale-version', 'pc.disabled-subject-cleaned', 'pc.linked-department-create', 'pcA.login', 'pc.rules-page-without-epoch', 'pcB.initial', 'pc.json-trailing', 'pcA.school', 'pcB.tenant', 'pc.capabilities-replay-no-write', 'pc.duplicate-natural', 'pc.same-user-other-authority', 'pc.anonymous', 'pc.dataset-folder-reject', 'pc.capabilities-old-admin-denied', 'pcB.school', 'pc.org-write', 'pc.dataset-id', 'pcA.initial', 'pc.replay-old-epoch', 'pc.replay', 'pc.resource-cross-forward', 'pc.school-page-cross', 'pc.unknown-school', 'pc.rule-immutable', 'pcB.user', 'pc.linked-department-grant', 'pc.rules-stale-epoch', 'pc.duplicate-school', 'pc.dataset-exact', 'pc.role-write', 'pc.read-does-not-write', 'pc.rule-delete-create', 'pc.user-write', 'pc.replay-changed', 'pc.revoked-replay-denied', 'pc.resource-create-other', 'pc.allow-deny-independent', 'pc.replay-epochs', 'pc.delegate-configuration', 'pc.catalog-dashboard', 'pc.subject-cross-forward', 'pc.idempotency-expired', 'pc.capabilities-read', 'pc.assignment-on-user', 'pc.disabled-subject-delete', 'pcDelegate.initial', 'pcA.reset', 'pc.delegate-member', 'pc.rule-cas', 'pc.delegate-select', 'pc.json-null', 'pc.unavailable-department-no-write', 'pc.idempotency-user-isolation', 'pc.school-cross-forward', 'pcB.select', 'pc.capabilities-write', 'pc.school-page', 'pc.capabilities-delegate-read', 'pcDelegate.reset', 'pc.config-granted-controlled-view', 'pc.controlled-payload-only', 'pc.config-never-opens-edit', 'pc.rules-read', 'pc.empty-school', 'pc.resource-cross-reverse', 'pc.rules-read-state', 'pc.idempotency-group-isolation', 'pc.role-create', 'pcA.tenant', 'pcA.user', 'pc.same-user-other-switch', 'pcDelegate.user', 'pc.org-cap-empty-read', 'pc.delegate-own-write', 'pc.org-empty-read', 'pc.rule-update'}


W04_REGRESSIONS.update({'PermissionDecisionTest.emptyAssignmentNoGrantAndInvalidActionsFailClosed', 'ManagementHttpBoundaryTest.w04DecisionRoleSchoolPairsAndSourcesMatchCurrentNativeDataset', 'PermissionDecisionTest.exportAndDrillIntersectOrdinaryView', 'ManagementHttpBoundaryTest.w04DecisionPlatformViewQualificationIsExplicitAndOtherActionsRemainOrdinary', 'PermissionDecisionTest.personalAndOrganizationAdditionsDoNotReplaceRoleScope', 'ManagementHttpBoundaryTest.w04DecisionPreviewAndControlledResourceSharePolicyWithoutOpeningPayload', 'ManagementHttpBoundaryTest.w04DecisionPreviewRejectsForgedTargetsResourcesAndRevisionWithoutWriting', 'PermissionDecisionTest.matchingDeniesOnlySubtractTheirSchoolAndAction', 'PermissionDecisionTest.resourceEditingRequiresSeparateViewAndNeverImpliesExport', 'PermissionDecisionTest.schoolCopyRequiresItsOwnSchoolAndView', 'PermissionDecisionTest.platformExceptionIsViewOnlyAndDoesNotDependOnOrdinaryMembership', 'PermissionDecisionTest.rolesKeepSchoolPairsBeforeUnion', 'ManagementHttpBoundaryTest.w04DecisionOrganizationPersonalDenyAndOperationPrerequisites', 'PermissionDecisionTest.unknownAndUnavailableSchoolsNeverSurviveIntersection', 'PermissionDecisionTest.factsAndSourcesAreDetachedImmutableSnapshots'})
UNIT_SUITES.update({'PermissionDecisionTest':12})
DECISION_HTTP_CASES = {'pd.finance-grant', 'pd.teaching-no-cross-product', 'pdA.reset', 'pd.personal-export-deny', 'pd.member-disable', 'pd.runtime-unchanged', 'pd.org-combined-sources', 'pd.deny-overrides-only-export', 'pdA.school', 'pd.finance-preview', 'pd.legacy-chartData/export', 'pd.no-parent-inheritance', 'pd.foreign-resource', 'pc.operator-login', 'pd.dashboard-create', 'pdViewer.initial', 'pd.org-member-revoke', 'pd.viewer-select', 'pdA.user', 'pd.principal-role', 'pdA.tenant', 'pd.legacy-datasetData/previewData', 'pd.legacy-embedded/info', 'pd.export-preview', 'pd.org-revoked-preview', 'pd.old-chart-denied', 'pd.org-immediate', 'pdViewer.reset', 'pd.platform-export-preview', 'pdB.tenant', 'pdB.school', 'pd.drill-preview', 'pd.finance-pairs', 'pd.foreign-target', 'pd.legacy-link/info', 'pd.view-after-export-deny', 'pdB.reset', 'pd.assignment-revoke', 'pd.old-session-revoked', 'pd.finance-role', 'pd.disabled-member-no-policy', 'pd.disabled-member-preview', 'pd.view-default-denied', 'pd.dashboard-read', 'pdViewer.user', 'pd.dashboard-deny', 'pdA.initial', 'pd.legacy-visualization/findById', 'pd.view-still-allowed', 'pd.reverse-resource', 'pd.department-grant', 'pd.teaching-after-revoke', 'pd.disabled-member-old-session', 'pd.org-member', 'pd.dashboard-preview', 'pd.preview-read-consistent', 'pd.personal-view-deny', 'pd.platform-view-only', 'pdB.select', 'pd.parent-org-grant', 'pd.parent-org-preview', 'pdViewer.login', 'pd.department-create', 'pdB.login', 'pd.principal-grant', 'pd.drill-view-intersection', 'pd.ordinary-preview-denied', 'pd.json-numeric', 'pd.member', 'pd.principal-assignment', 'pd.org-combined-preview', 'pdB.user', 'pdB.initial', 'pd.finance-assignment', 'pd.assignment-immediate', 'pd.platform-export-denied', 'pd.legacy-visualization/save', 'pd.platform-preview', 'pd.dashboard-view', 'pd.school2', 'pdA.select', 'pd.legacy-task/page', 'pd.teaching-preview', 'pdA.login'}

W04_REGRESSIONS.update({'ManagementHttpBoundaryTest.w04RevocationOldSessionsRecheckRulesRolesOrganizationsAndMembership', 'ManagementHttpBoundaryTest.w04RevocationPreviewTargetsNeverReplaceWorkerIdentityOrOpenLegacyRoutes', 'ManagementHttpBoundaryTest.w04RevocationFactReadSerializesWithPermissionWriteAndRejectsStaleEpoch', 'ManagementHttpBoundaryTest.w04RevocationExplicitSchoolDependencyCannotRemoveManagementDeny'})

W04_REGRESSIONS.update({'PermissionDecisionTest.excessiveSourceExpansionAndUnknownResourceKindsDenyEntireDecision', 'PermissionDecisionTest.platformOtherOperationsRequireMembershipAndExplicitActionWhileUsingActualView'})

def validate_unit_suite(doc, name):
    count = int(doc.attrib['tests'])
    cases = doc.findall('testcase')
    require(count >= UNIT_SUITES[name] and count == len(cases)
            and all(int(doc.attrib[k]) == 0 for k in ['errors', 'failures', 'skipped'])
            and all(not any(case.find(k) is not None for k in ['failure', 'error', 'skipped']) for case in cases),
            'UNIT_CASES_NOT_EXECUTED_OR_FAILED')
    observed = {name + '.' + case.attrib['name'] for case in cases}
    expected = {case for case in SCHEMA_REGRESSIONS if case.startswith(name + '.')}
    require(expected <= observed, 'SCHEMA_REGRESSION_CASES_MISSING')
    expected_jpa = {case for case in JPA_REGRESSIONS if case.startswith(name + '.')}
    require(expected_jpa <= observed, 'JPA_REGRESSION_CASES_MISSING')
    expected_mapping = {case for case in MAPPING_REGRESSIONS if case.startswith(name + '.')}
    require(expected_mapping <= observed, 'MAPPING_REGRESSION_CASES_MISSING')
    expected_organization = {case for case in ORGANIZATION_REGRESSIONS if case.startswith(name + '.')}
    require(expected_organization <= observed, 'ORGANIZATION_REGRESSION_CASES_MISSING')
    expected_evolution = {case for case in EVOLUTION_REGRESSIONS if case.startswith(name + '.')}
    require(expected_evolution <= observed, 'EVOLUTION_REGRESSION_CASES_MISSING')
    expected_audit = {case for case in AUDIT_REGRESSIONS if case.startswith(name + '.')}
    require(expected_audit <= observed, 'AUDIT_REGRESSION_CASES_MISSING')
    require({case for case in W03_REGRESSIONS if case.startswith(name + '.')} <= observed, 'W03_REGRESSION_CASES_MISSING')
    require({case for case in W04_REGRESSIONS if case.startswith(name + '.')} <= observed, 'W04_REGRESSION_CASES_MISSING')
    require({case for case in GENERATED_REGRESSIONS if case.startswith(name + '.')} <= observed, 'GENERATED_REGRESSION_CASES_MISSING')
    require({case for case in GRANT_STORAGE_REGRESSIONS if case.startswith(name + '.')} <= observed, 'GRANT_STORAGE_CASES_MISSING')
    require({case for case in IDEMPOTENCY_STORAGE_REGRESSIONS if case.startswith(name + '.')} <= observed, 'IDEMPOTENCY_STORAGE_CASES_MISSING')
    return count


def digest(data):
    return hashlib.sha256(data).hexdigest()


def control_snapshot():
    home = ROOT / 'runtime/w03-control-home'
    pid = int((home / 'app.pid').read_text())
    proc = Path('/proc') / str(pid)
    require(proc.stat().st_uid == os.getuid(), 'WRONG_CONTROL_PROCESS_OWNER')
    args = [v.decode() for v in proc.joinpath('cmdline').read_bytes().split(b'\0') if v]
    require(args == json.loads((home / 'arguments.json').read_text()) and '--server.port=18120' in args
            and '-Duser.home=' + str(home) in args and not any('bootstrap-file=' in v for v in args), 'WRONG_CONTROL_RUNTIME')
    config = home / 'opt/dataease3.0/config/application.yml'
    require('--spring.config.additional-location=file:' + str(config) in args, 'CONTROL_CONFIGURATION_NOT_UNIFIED')
    ticks = proc.joinpath('stat').read_text().split(') ')[1].split()[19]
    boot = next(int(line.split()[1]) for line in Path('/proc/stat').read_text().splitlines() if line.startswith('btime '))
    require(JAR.stat().st_mtime <= boot + int(ticks) / os.sysconf('SC_CLK_TCK'), 'CONTROL_JAR_REPLACED_AFTER_START')
    with urllib.request.urlopen('http://127.0.0.1:18120/de2api/api/enterprise/v1/ready', timeout=5) as response:
        require(json.load(response).get('code') == 0, 'CONTROL_NOT_READY')
    return {'pid': pid, 'processStartTicks': ticks, 'argumentsSha256': digest((home / 'arguments.json').read_bytes()),
            'configurationSha256': digest(config.read_bytes())}


def validate_build_binding(build, source_hash, jar_hash):
    require(isinstance(build, dict) and build.get('passed') is True and build.get('state') == 'PASSED', 'PRODUCT_BUILD_NOT_PASSED')
    require(build.get('sourceSha256') == source_hash and build.get('jarSha256') == jar_hash, 'PRODUCT_BUILD_BINDING_MISMATCH')
    stages = build.get('stages', {})
    require(isinstance(stages, dict) and set(stages) == {'sdk', 'frontend', 'backend'}
            and all(isinstance(value, dict) and value.get('passed') is True and value.get('exitCode') == 0 for value in stages.values()), 'PRODUCT_BUILD_STAGE_PROOF_MISSING')


def snapshot():
    require(Path(__file__).resolve().parent == TOOLS, 'WRONG_TASK_DIRECTORY')
    pid = int((ROOT / 'runtime/app.pid').read_text())
    proc = Path('/proc') / str(pid)
    require(proc.stat().st_uid == os.getuid(), 'WRONG_PROCESS_OWNER')
    args = proc.joinpath('cmdline').read_bytes().rstrip(b'\0').split(b'\0')
    args = [item.decode() for item in args]
    require('-jar' in args and args[args.index('-jar') + 1] == str(JAR), 'WRONG_TASK_JAR')
    require('-Duser.home=' + str(ROOT / 'runtime/app-home') in args, 'WRONG_APP_HOME')
    require('--spring.config.additional-location=file:' + str(
        ROOT / 'runtime/app-home/opt/dataease3.0/config/application.yml') in args,
        'WRONG_CONFIG_LOCATION')
    ticks = proc.joinpath('stat').read_text().split(') ')[1].split()[19]
    boot = next(int(line.split()[1]) for line in Path('/proc/stat').read_text().splitlines()
                if line.startswith('btime '))
    started = boot + int(ticks) / os.sysconf('SC_CLK_TCK')
    require(JAR.stat().st_mtime <= started, 'JAR_REPLACED_AFTER_PROCESS_STARTED')
    with urllib.request.urlopen('http://127.0.0.1:18100/de2api/xpackModel', timeout=5) as res:
        model = json.loads(res.read())
        require(res.status == 200 and model.get('code') == 0 and 'data' in model
                and model['data'] is None, 'NOT_CONFIRMED_COMMUNITY_TEST_APP')
    files = subprocess.check_output(['git', 'ls-files', '--', 'core', 'sdk'], cwd=SOURCE)
    rows = []
    names = set(files.decode().splitlines()) | set(["core/core-frontend/vite.bounded.config.ts","core/core-backend/src/main/java/io/dataease/enterprise/foundation/FoundationSchemaV47.java","core/core-backend/src/main/java/io/dataease/enterprise/foundation/EnterpriseIdempotencySqlBlock.java","core/core-backend/src/main/java/io/dataease/enterprise/permission/persistence/EnterpriseIdempotency.java","core/core-backend/src/test/java/io/dataease/enterprise/foundation/IdempotencyStorageTest.java","core/core-backend/src/main/java/io/dataease/enterprise/foundation/FoundationSchemaV46.java","core/core-backend/src/main/java/io/dataease/enterprise/foundation/EnterpriseGrantSqlBlock.java","core/core-backend/src/main/java/io/dataease/enterprise/permission/persistence/EnterpriseGrant.java","core/core-backend/src/main/java/io/dataease/enterprise/permission/persistence/EnterpriseGrantSchool.java","core/core-backend/src/test/java/io/dataease/enterprise/foundation/PermissionStorageFixture.java","core/core-backend/src/test/java/io/dataease/enterprise/foundation/GrantStorageTest.java","core/core-backend/src/test/java/io/dataease/enterprise/foundation/GeneratedColumnSchemaTest.java","sdk/api/api-permissions/src/main/java/io/dataease/api/permissions/enterprise/RoleManagementApi.java","core/core-backend/src/main/java/io/dataease/enterprise/management/manage/ManagementPrivilegeGuard.java","core/core-backend/src/main/java/io/dataease/enterprise/management/manage/RoleManagementService.java","core/core-backend/src/main/java/io/dataease/enterprise/management/server/RoleManagementServer.java"])
    for part in ["core/core-backend/src/main/java/io/dataease/enterprise", "core/core-backend/src/test/java/io/dataease/enterprise", "sdk/api/api-permissions/src/main/java/io/dataease/api/permissions/enterprise"]:
        names.update(str(p.relative_to(SOURCE)) for p in (SOURCE / part).rglob("*.java"))
    for name in sorted(names):
        if name == 'core/core-frontend/auto-imports.d.ts' or '/resources/static/' in name:
            continue
        path = SOURCE / name
        require(path.is_file(), 'MISSING_TRACKED_PRODUCT_SOURCE')
        rows.append(name + ':' + digest(path.read_bytes()))
    source_hash = digest('\n'.join(rows).encode())
    jar_hash = digest(JAR.read_bytes())
    validate_build_binding(json.loads((ROOT / 'logs/safe-build-results.json').read_text()), source_hash, jar_hash)
    assets = {}
    entries = {}
    with zipfile.ZipFile(JAR) as archive:
        for html in ['index.html', 'mobile.html']:
            member = 'BOOT-INF/classes/static/' + html
            contents = archive.read(member)
            entry = '/' if html == 'index.html' else '/mobile.html'
            assets[entry] = digest(contents)
            entries['desktop' if html == 'index.html' else 'mobile'] = [entry]
            for url in re.findall(r'(?:src|href)=["\']([^"\']+)["\']', contents.decode()):
                require(url.startswith('./'), 'UNKNOWN_STATIC_ASSET_LAYOUT')
                assets['/' + url[2:]] = digest(archive.read('BOOT-INF/classes/static/' + url[2:]))
                entries['desktop' if html == 'index.html' else 'mobile'].append('/' + url[2:])
    return {
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE).decode().strip(),
        'jarSha256': jar_hash, 'sourceSha256': source_hash,
        'toolSha256': digest('\n'.join(name + ':' + digest((TOOLS / name).read_bytes())
                                     for name in TOOL_NAMES).encode()),
        'pid': pid, 'processStartTicks': ticks, 'assets': assets, 'entryAssets': entries,
        'environment': 'w03-community-18100-control-18120', 'controlRuntime': control_snapshot()
    }


def validate_database_capacity(port, limit, connected):
    require(port == 13306, 'WRONG_CAPACITY_DATABASE')
    require(0 <= connected <= limit and limit - connected >= 12, 'INSUFFICIENT_DATABASE_HEADROOM')
    return {'port': port, 'limit': limit, 'connected': connected, 'minimumHeadroom': 12}


def checks(run_id):
    require(str(uuid.UUID(run_id)) == run_id, 'INVALID_RUN_ID')
    before = snapshot()
    out = ROOT / 'logs' / ('delivery-' + run_id)
    out.mkdir(exist_ok=False)
    # A new attempt immediately invalidates the previous success. A later failure
    # must never leave an earlier same-HEAD success usable by pre-push.
    (ROOT / 'logs/delivery-gate.json').write_text(json.dumps({
        'schemaVersion': 1, 'passed': False, 'state': 'RUNNING', 'runId': run_id,
        'identity': before, 'finishedUnix': time.time()
    }))
    env = os.environ.copy()
    env['JAVA_HOME'] = '/usr/lib/jvm/java-21'
    env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
    start = time.time()
    spec = importlib.util.spec_from_file_location('capacity_boundary', TOOLS / 'verify-database-boundary.py')
    boundary = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(boundary)
    capacity = boundary.query('root', "SELECT @@port,@@max_connections,(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected');")
    require(capacity.returncode == 0, 'DATABASE_CAPACITY_UNAVAILABLE')
    capacity_record = validate_database_capacity(*map(int, capacity.stdout.strip().split('\t')))
    resource_spec = importlib.util.spec_from_file_location('resource_job', TOOLS / 'resource-job.py')
    resource_job = importlib.util.module_from_spec(resource_spec)
    resource_spec.loader.exec_module(resource_job)
    commands = [
        ('resource', ['python3', '-B', '-E', 'tools/phase1/verify-resource-job.py']),
        ('unit', ['python3', '-B', '-E', 'tools/phase1/resource-job.py', 'unit']),
        ('hmac', ['node', 'tools/phase1/login-startup-regression.cjs']),
        ('receiptGuard', ['python3', '-B', '-E', 'tools/phase1/test-delivery-gate.py']),
        ('api', ['node', 'tools/phase1/community-compatibility.cjs']),
        ('database', ['python3', '-E', 'tools/phase1/verify-database-boundary.py']),
        ('foundation', ['python3', '-B', '-E', 'tools/phase1/verify-foundation.py']),
        ('control', ['python3', '-B', '-E', 'tools/phase1/verify-w03-control.py', 'all']),
        ('w04', ['python3', '-B', '-E', 'tools/phase1/verify-w04-roles.py']),
        ('permissions', ['python3', '-B', '-E', 'tools/phase1/verify-w04-permissions.py']),
        ('decisions', ['python3', '-B', '-E', 'tools/phase1/verify-w04-decisions.py']),
        ('storage', ['python3', '-B', '-E', 'tools/phase1/verify-w04-storage.py'])
    ]
    results = {}
    for name, cmd in commands:
        with (out / (name + '.log')).open('wb') as stream:
            result = subprocess.run(cmd, cwd=SOURCE, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=900 if name in {'unit', 'storage'} else 180)
        require(result.returncode == 0, 'CHECK_FAILED_' + name.upper())
        results[name] = {'passed': True}
    permissions_path = ROOT / 'logs/w04-step4-http.json'
    require(permissions_path.stat().st_mtime >= start, 'STALE_PERMISSION_REPORT')
    permissions = json.loads(permissions_path.read_text())
    validate_permission_report(permissions, before)
    require(start <= permissions['startedUnix'] <= permissions['finishedUnix'] <= time.time() + 5, 'STALE_PERMISSION_REPORT')
    (out / 'w04-step4-http.json').write_text(json.dumps(permissions, indent=2))
    results['permissions'] = {'passed': True, 'cases': len(permissions['cases']), 'requiredCases': sorted(PERMISSION_HTTP_CASES),
                              'identity': before, 'runId': permissions['runId']}
    decision_path = ROOT / 'logs/w04-decisions-http.json'
    require(decision_path.stat().st_mtime >= start, 'STALE_DECISION_REPORT')
    decisions = json.loads(decision_path.read_text())
    validate_decision_report(decisions, before)
    require(start <= decisions['startedUnix'] <= decisions['finishedUnix'] <= time.time() + 5, 'STALE_DECISION_REPORT')
    (out / 'w04-decisions-http.json').write_text(json.dumps(decisions, indent=2))
    results['decisions'] = {'passed': True, 'cases': len(decisions['cases']), 'requiredCases': sorted(DECISION_HTTP_CASES), 'identity': before, 'runId': decisions['runId']}
    storage_path = ROOT / 'logs/w04-storage-results.json'
    require(storage_path.stat().st_mtime >= start, 'STALE_STORAGE_REPORT')
    storage = json.loads(storage_path.read_text())
    validate_storage_report(storage, before)
    (out / 'w04-storage-results.json').write_text(json.dumps(storage, indent=2))
    results['storage'] = {'passed': True, 'cases': len(storage['cases']),
                          'requiredCases': sorted(STORAGE_CASES), 'identity': before, 'runId': storage['runId']}
    resource_path = ROOT / 'logs/resource-verification-results.json'
    require(resource_path.stat().st_mtime >= start, 'STALE_RESOURCE_REPORT')
    resource_report = json.loads(resource_path.read_text())
    validate_resource_report(resource_report, before)
    (out / 'resource-verification-results.json').write_text(json.dumps(resource_report, indent=2))
    results['resource'] = {'passed': True, 'cases': len(resource_report['cases']),
                           'requiredCases': sorted(RESOURCE_CASES), 'identity': before, 'runId': resource_report['runId']}
    total = 0
    for name in UNIT_SUITES:
        paths = list((SOURCE / 'core/core-backend/target/surefire-reports').glob('TEST-*.' + name + '.xml'))
        require(len(paths) == 1 and paths[0].stat().st_mtime >= start, 'STALE_OR_MISSING_UNIT_REPORT')
        doc = ET.parse(paths[0]).getroot()
        total += validate_unit_suite(doc, name)
    results['unit']['cases'] = total
    results['unit']['databaseCapacity'] = capacity_record
    results['unit']['schemaRegressions'] = sorted(SCHEMA_REGRESSIONS)
    results['unit']['jpaRegressions'] = sorted(JPA_REGRESSIONS)
    results['unit']['mappingRegressions'] = sorted(MAPPING_REGRESSIONS)
    results['unit']['organizationRegressions'] = sorted(ORGANIZATION_REGRESSIONS)
    results['unit']['evolutionRegressions'] = sorted(EVOLUTION_REGRESSIONS)
    results['unit']['auditRegressions'] = sorted(AUDIT_REGRESSIONS)
    results['unit']['w03Regressions'] = sorted(W03_REGRESSIONS)
    results['unit']['w04Regressions'] = sorted(W04_REGRESSIONS)
    results['unit']['generatedRegressions'] = sorted(GENERATED_REGRESSIONS)
    results['unit']['grantStorageRegressions'] = sorted(GRANT_STORAGE_REGRESSIONS)
    results['unit']['idempotencyStorageRegressions'] = sorted(IDEMPOTENCY_STORAGE_REGRESSIONS)
    hmac_log = (out / 'hmac.log').read_text()
    require('5 passed' in hmac_log, 'HMAC_CASE_COUNT_MISSING')
    results['hmac']['cases'] = 5
    guard_log = (out / 'receiptGuard.log').read_text()
    match = re.search(r'Ran (\d+) tests', guard_log)
    require(match and int(match.group(1)) >= 76 and '\nOK\n' in guard_log, 'RECEIPT_GUARD_TESTS_MISSING')
    results['receiptGuard']['cases'] = int(match.group(1))
    for name, filename, expected in [('api', 'community-api-results.json', 4),
                                     ('database', 'database-boundary-results.json', 14),
                                     ('foundation', 'foundation-results.json', 25)]:
        path = ROOT / 'logs' / filename
        require(path.stat().st_mtime >= start, 'STALE_' + name.upper() + '_REPORT')
        body = path.read_bytes()
        data = json.loads(body)
        # Existing tools exit nonzero on failed behavior; additionally reject an empty report.
        count = len(data) if isinstance(data, list) else len(data.get('checks', []))
        require(count == expected, 'WRONG_' + name.upper() + '_CASE_COUNT')
        (out / filename).write_bytes(body)
        results[name]['cases'] = count
    control_path = ROOT / 'logs/w03-control-all-results.json'
    require(control_path.stat().st_mtime >= start, 'STALE_CONTROL_REPORT')
    control = json.loads(control_path.read_text())
    require(control.get('passed') is True and control.get('phase') == 'all' and control['head'] == before['head']
            and control['jarSha256'] == before['jarSha256'] and control['pid'] == before['controlRuntime']['pid'], 'CONTROL_REPORT_IDENTITY_MISMATCH')
    observed = {case['id'] for case in control['cases'] if case.get('status') == 'passed'}
    require(len(observed) == len(control['cases']) and len(observed) >= 80 and W03_HTTP_CASES <= observed, 'CONTROL_CASES_MISSING_OR_FAILED')
    (out / 'w03-control-all-results.json').write_text(json.dumps(control, indent=2))
    results['control'] = {'passed': True, 'cases': len(observed), 'requiredCases': sorted(W03_HTTP_CASES),
                          'pid': control['pid'], 'jarSha256': control['jarSha256'], 'head': control['head']}

    w04_path = ROOT / 'logs/w04-step2-http.json'
    require(w04_path.stat().st_mtime >= start, 'STALE_W04_REPORT')
    w04 = json.loads(w04_path.read_text())
    require(w04.get('passed') is True and w04.get('head') == before['head']
            and w04.get('jarSha256') == before['jarSha256']
            and w04.get('controlRuntime') == before['controlRuntime'], 'W04_REPORT_IDENTITY_MISMATCH')
    w04_cases = {case['id'] for case in w04['cases'] if case.get('status') == 'passed'}
    require(len(w04_cases) == len(w04['cases']) and W04_HTTP_CASES <= w04_cases, 'W04_CASES_MISSING_OR_FAILED')
    (out / 'w04-step2-http.json').write_text(json.dumps(w04, indent=2))
    results['w04'] = {'passed': True, 'cases': len(w04_cases), 'requiredCases': sorted(W04_HTTP_CASES),
                      'head': w04['head'], 'jarSha256': w04['jarSha256'], 'controlRuntime': w04['controlRuntime']}

    for name, switch, marker, property_name in [
            ('missing-services', 'true', 'Enterprise security assembly rejected', 'enterprise.enabled'),
            ('invalid-switch', 'tru', 'enterprise.enabled must be explicitly true or false', 'enterprise.enabled'),
            ('invalid-foundation', 'tru', 'enterprise.foundation.enabled must be explicitly true or false', 'enterprise.foundation.enabled')]:
        cmd = [env['JAVA_HOME'] + '/bin/java', '-Duser.home=' + str(ROOT / 'runtime/gate-home'),
               '-jar', str(JAR), '--spring.config.additional-location=file:' + str(
                   ROOT / 'runtime/gate-home/opt/dataease3.0/config/application.yml'),
               '--' + property_name + '=' + switch]
        if property_name != 'enterprise.enabled':
            cmd.append('--enterprise.enabled=false')
        path = out / ('gate-' + name + '.log')
        with path.open('wb') as stream:
            result = resource_job.run('assembly-refusal', cmd, ROOT, stdout=stream, timeout=45)
        content = path.read_text()
        require(result.get('exitCode') == 1 and marker in content and not any(word in content for word in
                ['HikariPool', 'Initialized JPA EntityManagerFactory', 'Tomcat started']), 'WRONG_GATE_REFUSAL')
    results['enterpriseRefusal'] = {'passed': True, 'cases': 3}
    require(snapshot() == before, 'RUNTIME_OR_SOURCE_CHANGED_DURING_CHECKS')
    receipt = {'runId': run_id, 'identity': before, 'checks': results}
    (out / 'remote-checks.json').write_text(json.dumps(receipt, indent=2))
    return receipt


def validate_resource_report(resource, identity):
    require(resource.get('schemaVersion') == 1 and resource.get('passed') is True
            and resource.get('prebuild') is False, 'RESOURCE_NOT_PASSED')
    require(resource.get('identity') == identity, 'RESOURCE_RECEIPT_IDENTITY_MISMATCH')
    observed = {case['id'] for case in resource.get('cases', []) if case.get('status') == 'passed'}
    require(len(observed) == len(resource.get('cases', [])) and observed == RESOURCE_CASES,
            'RESOURCE_CASES_MISSING_OR_FAILED')


def validate_storage_report(storage, identity):
    require(storage.get('passed') is True and storage.get('state') == 'PASSED'
            and storage.get('schemaVersion') == 1, 'STORAGE_NOT_PASSED')
    require(storage.get('identity') == identity, 'STORAGE_RECEIPT_IDENTITY_MISMATCH')
    require(str(uuid.UUID(storage['runId'])) == storage['runId'], 'INVALID_STORAGE_RUN_ID')
    observed = {case['id'] for case in storage.get('cases', []) if case.get('status') == 'passed'}
    require(len(observed) == len(storage.get('cases', [])) and observed == STORAGE_CASES,
            'STORAGE_CASES_MISSING_OR_FAILED')


def validate_permission_report(report, identity):
    require(report.get('schemaVersion') == 1 and report.get('passed') is True, 'PERMISSION_NOT_PASSED')
    require(report.get('identity') == identity, 'PERMISSION_RECEIPT_IDENTITY_MISMATCH')
    require(str(uuid.UUID(report['runId'])) == report['runId'], 'INVALID_PERMISSION_RUN_ID')
    observed = {case['id'] for case in report.get('cases', []) if case.get('status') == 'passed'}
    require(len(observed) == len(report.get('cases', [])) and observed == PERMISSION_HTTP_CASES,
            'PERMISSION_CASES_MISSING_OR_FAILED')


def validate_decision_report(report, identity):
    require(report.get('schemaVersion') == 1 and report.get('passed') is True, 'DECISION_NOT_PASSED')
    require(report.get('identity') == identity, 'DECISION_RECEIPT_IDENTITY_MISMATCH')
    require(str(uuid.UUID(report['runId'])) == report['runId'], 'INVALID_DECISION_RUN_ID')
    observed = {case['id'] for case in report.get('cases', []) if case.get('status') == 'passed'}
    require(len(observed) == len(report.get('cases', [])) and observed == DECISION_HTTP_CASES,
            'DECISION_CASES_MISSING_OR_FAILED')


def verify_gate(head=None):
    path = ROOT / 'logs/delivery-gate.json'
    require(path.is_file(), 'DELIVERY_RECEIPT_MISSING')
    report = json.loads(path.read_text())
    require(report.get('schemaVersion') == 1 and report.get('passed') is True, 'DELIVERY_NOT_PASSED')
    require(0 <= time.time() - report['finishedUnix'] <= 3600, 'DELIVERY_RECEIPT_EXPIRED')
    require(report['identity'] == snapshot(), 'DELIVERY_SOURCE_OR_RUNTIME_CHANGED')
    if head:
        require(report['identity']['head'] == head, 'PUSH_HEAD_NOT_TESTED')
    require(isinstance(report.get('checks'), dict), 'INVALID_CHECKS_ROOT')
    protocol = report.get('protocol', {})
    cases = protocol.get('cases', [])
    require(protocol.get('schemaVersion') == 1 and protocol.get('passed') is True
            and len(cases) == len(PROTOCOL_CASES)
            and {case.get('id') for case in cases} == PROTOCOL_CASES
            and all(case.get('status') == 'passed' for case in cases),
            'PROTOCOL_CHECKS_MISSING_OR_FAILED')
    require(protocol.get('sources') == protocol_sources(), 'PROTOCOL_SOURCE_MISMATCH')
    run_id = report['runId']
    require(str(uuid.UUID(run_id)) == run_id, 'INVALID_RUN_ID')
    remote = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'remote-checks.json').read_text())
    require(remote['identity'] == report['identity'] and remote['checks'] == report['checks'],
            'REMOTE_CHECK_RECEIPT_MISMATCH')
    for name, minimum in [('unit', sum(UNIT_SUITES.values())), ('hmac', 5), ('api', 4), ('database', 14), ('enterpriseRefusal', 3), ('receiptGuard', 76), ('foundation', 25), ('control', 80), ('w04', len(W04_HTTP_CASES)), ('permissions', len(PERMISSION_HTTP_CASES)), ('decisions', len(DECISION_HTTP_CASES)), ('storage', len(STORAGE_CASES)), ('resource', len(RESOURCE_CASES))]:
        item = report['checks'].get(name, {})
        require(item.get('passed') is True and item.get('cases', 0) >= minimum, 'REQUIRED_CHECK_MISSING')
    require(set(report['checks']['unit'].get('schemaRegressions', [])) == SCHEMA_REGRESSIONS,
            'SCHEMA_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('jpaRegressions', [])) == JPA_REGRESSIONS,
            'JPA_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('mappingRegressions', [])) == MAPPING_REGRESSIONS,
            'MAPPING_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('organizationRegressions', [])) == ORGANIZATION_REGRESSIONS,
            'ORGANIZATION_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('evolutionRegressions', [])) == EVOLUTION_REGRESSIONS,
            'EVOLUTION_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('auditRegressions', [])) == AUDIT_REGRESSIONS,
            'AUDIT_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('w03Regressions', [])) == W03_REGRESSIONS, 'W03_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('w04Regressions', [])) == W04_REGRESSIONS, 'W04_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('generatedRegressions', [])) == GENERATED_REGRESSIONS, 'GENERATED_REGRESSION_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('grantStorageRegressions', [])) == GRANT_STORAGE_REGRESSIONS, 'GRANT_STORAGE_RECEIPT_MISSING')
    require(set(report['checks']['unit'].get('idempotencyStorageRegressions', [])) == IDEMPOTENCY_STORAGE_REGRESSIONS,
            'IDEMPOTENCY_STORAGE_RECEIPT_MISSING')
    resource = report['checks']['resource']
    require(set(resource.get('requiredCases', [])) == RESOURCE_CASES, 'RESOURCE_CASE_RECEIPT_MISSING')
    require(resource.get('identity') == report['identity'], 'RESOURCE_RECEIPT_IDENTITY_MISMATCH')
    original_resource = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'resource-verification-results.json').read_text())
    validate_resource_report(original_resource, report['identity'])
    require(original_resource['runId'] == resource.get('runId'), 'RESOURCE_RUN_ID_MISMATCH')
    permissions = report['checks']['permissions']
    require(set(permissions.get('requiredCases', [])) == PERMISSION_HTTP_CASES, 'PERMISSION_CASE_RECEIPT_MISSING')
    require(permissions.get('identity') == report['identity'], 'PERMISSION_RECEIPT_IDENTITY_MISMATCH')
    original_permissions = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'w04-step4-http.json').read_text())
    validate_permission_report(original_permissions, report['identity'])
    require(original_permissions['runId'] == permissions.get('runId'), 'PERMISSION_RUN_ID_MISMATCH')
    decisions = report['checks']['decisions']
    require(set(decisions.get('requiredCases', [])) == DECISION_HTTP_CASES, 'DECISION_CASE_RECEIPT_MISSING')
    require(decisions.get('identity') == report['identity'], 'DECISION_RECEIPT_IDENTITY_MISMATCH')
    original_decisions = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'w04-decisions-http.json').read_text())
    validate_decision_report(original_decisions, report['identity'])
    require(original_decisions['runId'] == decisions.get('runId'), 'DECISION_RUN_ID_MISMATCH')
    storage = report['checks']['storage']
    require(set(storage.get('requiredCases', [])) == STORAGE_CASES, 'STORAGE_CASE_RECEIPT_MISSING')
    require(storage.get('identity') == report['identity'], 'STORAGE_RECEIPT_IDENTITY_MISMATCH')
    original_storage = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'w04-storage-results.json').read_text())
    validate_storage_report(original_storage, report['identity'])
    require(original_storage['runId'] == storage.get('runId'), 'STORAGE_RUN_ID_MISMATCH')
    w04 = report['checks']['w04']
    require(set(w04.get('requiredCases', [])) == W04_HTTP_CASES, 'W04_CASE_RECEIPT_MISSING')
    require(w04.get('head') == report['identity']['head'] and w04.get('jarSha256') == report['identity']['jarSha256']
            and w04.get('controlRuntime') == report['identity']['controlRuntime'], 'W04_RECEIPT_IDENTITY_MISMATCH')
    control = report['checks']['control']
    require(set(control.get('requiredCases', [])) == W03_HTTP_CASES, 'CONTROL_CASE_RECEIPT_MISSING')
    require(control.get('head') == report['identity']['head'] and control.get('jarSha256') == report['identity']['jarSha256']
            and control.get('pid') == report['identity']['controlRuntime']['pid'], 'CONTROL_RECEIPT_IDENTITY_MISMATCH')
    required = {kind + '.' + case for kind in ['desktop', 'mobile']
                for case in ['initialization', 'wrong-password', 'login', 'reload']}
    browser = report['browser']
    require(browser.get('runId') == run_id and browser.get('identity') == report['identity']
            and browser.get('fault') is None and browser.get('passed') is True, 'BROWSER_IDENTITY_MISMATCH')
    cases = browser.get('cases', [])
    require(len(cases) == 8 and {case['id'] for case in cases} == required
            and all(case.get('status') == 'passed' for case in cases), 'BROWSER_CASES_MISSING_OR_FAILED')
    require(not browser.get('apiErrors') and not browser.get('pageErrors')
            and not browser.get('assetErrors') and not browser.get('networkErrors'), 'BROWSER_ERRORS_PRESENT')
    negative = report.get('negativeControls', {})
    require(set(negative) == {'unexpected-404', 'loading-mask', 'mobile-submit-blocked'}
            and all(negative.values()), 'NEGATIVE_CONTROLS_MISSING')
    return {'passed': True, 'runId': run_id, 'head': report['identity']['head']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private', action='store_true')
    parser.add_argument('--checks')
    parser.add_argument('--verify-gate', action='store_true')
    parser.add_argument('--head')
    options = parser.parse_args()
    if options.checks:
        result = checks(options.checks)
    elif options.verify_gate:
        result = verify_gate(options.head)
    else:
        result = {'identity': snapshot(), 'serverUnix': time.time()}
        if options.private:
            credential = json.loads((ROOT / 'runtime/conf/test-credentials.json').read_text())['admin']
            require(isinstance(credential, str) and bool(credential), 'ADMIN_CREDENTIAL_MISSING')
            result['credential'] = {'username': 'admin', 'password': credential}
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Only fixed internal codes; never serialize configuration, credentials or subprocess output.
        code = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('Delivery check rejected: ' + code, file=sys.stderr)
        sys.exit(1)
