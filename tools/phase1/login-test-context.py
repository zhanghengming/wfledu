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
              'login-startup-regression.cjs', 'community-compatibility.cjs',
              'verify-database-boundary.py', 'verify-foundation.py', 'verify-w03-control.py', 'verify-w04-roles.py']


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


UNIT_SUITES.update({'ManagementConfigurationTest': 3, 'ManagementRequestBridgeTest': 3, 'AuthorityMigrationTest': 6, 'CredentialMigrationTest': 7, 'ManagementAuthorityTest': 12, 'ManagementHttpBoundaryTest': 16, 'ManagementResponseTest': 1, 'ManagementSessionTest': 11, 'ResourceMigrationTest': 4, 'PasswordCodecTest': 3})
W03_REGRESSIONS = set(['AuthorityMigrationTest.authorityDriftRefusesBeforeCreatingRemainingTables', 'AuthorityMigrationTest.committedFirstAuthorityTableFailureRetainsLedgerAndRetries', 'AuthorityMigrationTest.formalThreeStepEmptyPlanKeepsSuccessfulHistoryOnRestart', 'AuthorityMigrationTest.sixTableDescriptorsRemainImmutableAndFullyCommented', 'AuthorityMigrationTest.unknownSubjectTypeAndInvalidCapabilityCannotGrantAuthority', 'AuthorityMigrationTest.v42RowsSurviveRealListenerUpgradeWithoutReexecutingOldSteps', 'CredentialMigrationTest.committedCredentialDdlFailureRetainsFailedLedgerAndRetries', 'CredentialMigrationTest.credentialDriftRefusesBeforeCreatingRemainingTables', 'CredentialMigrationTest.explicitInitializationIsAtomicRestrictedAndCannotRepeat', 'CredentialMigrationTest.failedInitializationRollsBackAccountAndQualifications', 'CredentialMigrationTest.formalFourStepEmptyPlanRetainsHistoryAndProvidesNoCredentials', 'CredentialMigrationTest.privateInputRejectsPermissionsDuplicateKeysAndTrailingJson', 'CredentialMigrationTest.v43RowsSurviveListenerUpgradeWithoutOldStepsRepeating', 'ManagementAuthorityTest.disabledOrganizationAncestorDoesNotConferManagement', 'ManagementAuthorityTest.emptyDisabledOrNonSchoolRoleScopeRejects', 'ManagementAuthorityTest.explicitOrganizationGrantRequiresExactActiveMembership', 'ManagementAuthorityTest.explicitPersonalDenyOverridesOrganizationAllow', 'ManagementAuthorityTest.formalV43UpgradeRetainsRowsRepeatsAndRejectsCrossGroupSubject', 'ManagementAuthorityTest.membershipAndAdministratorRoleNameDoNotGrantManagement', 'ManagementAuthorityTest.missingContextAndMissingTransactionReject', 'ManagementAuthorityTest.otherGroupAndDisabledMemberCannotUseGrants', 'ManagementAuthorityTest.personalGrantEnablesRealOrganizationAndAuditTransaction', 'ManagementAuthorityTest.roleDenyOverridesPersonalAllowWithoutCombiningSchoolSets', 'ManagementAuthorityTest.roleGrantRequiresItsOwnActiveAssignmentAndSchools', 'ManagementAuthorityTest.staleIdentityTenantRevisionAndDisabledUserReject', 'ManagementConfigurationTest.defaultModeSuppliesNoIdentityOrRequestFilter', 'ManagementConfigurationTest.invalidSwitchRejectsBeforeAssembly', 'ManagementConfigurationTest.managementWithoutFoundationAndCombinedFullModeReject', 'ManagementHttpBoundaryTest.credentialsOriginsAndLegacyRoutesCannotBypassProductionFilter', 'ManagementHttpBoundaryTest.groupResourcesAndSchoolReferencesRejectBothDirectionsWithoutMetadata', 'ManagementHttpBoundaryTest.lastAdministratorAndImmutableSchoolAttributionCannotBeRemoved', 'ManagementHttpBoundaryTest.nativeOwnershipFailureRollsBackResourceEpochAndAuditThenRetries', 'ManagementHttpBoundaryTest.nativeOwnershipRequiresSelectedGroupAndNeverGrantsEditing', 'ManagementHttpBoundaryTest.platformQualificationsRemainIndependentAndRevocationIsImmediate', 'ManagementHttpBoundaryTest.realContextSwitchChecksMemberCasAndImmediateRevocation', 'ManagementHttpBoundaryTest.realLoginRefusesDuringMigrationAndOpensOnlyAfterInitialization', 'ManagementHttpBoundaryTest.realOrganizationMemberLifecycleHasAuditCasAndImmediateRevocation', 'ManagementHttpBoundaryTest.realPasswordChangeAndExpiryInvalidateExistingCredentials', 'ManagementHttpBoundaryTest.reusedRealHttpWorkerClearsGroupContextAfterRejectedRequests', 'ManagementHttpBoundaryTest.strictJsonRejectsUnknownDuplicateTypeTrailingAndOversizedBodies', 'ManagementRequestBridgeTest.activeScopeMatchesOnlyTheExactRequestAndClearsAfterException', 'ManagementRequestBridgeTest.headersAndPathsAloneNeverBypassNativeAuthentication', 'ManagementRequestBridgeTest.nestedOrOtherThreadScopesAndNonManagementPathsReject', 'ManagementResponseTest.downstreamCacheOverridesAndResetCannotExposeCredentialsToCaches', 'ManagementSessionTest.concurrentWrongPasswordsAllCountTowardsLock', 'ManagementSessionTest.corruptStoredParametersFailClosedWithoutCreatingSession', 'ManagementSessionTest.fiveFailuresLockAtomicallyAndExpiryRestartsCounter', 'ManagementSessionTest.forcedResetCannotSwitchAndPasswordChangeRevokesEveryOldSession', 'ManagementSessionTest.idleAndAbsoluteExpiryAndLogoutDenyFurtherUse', 'ManagementSessionTest.loginPersistsOnlyDigestAndReturnsUnselectedRestrictedIdentity', 'ManagementSessionTest.malformedTokensCannotSelectAnIdentity', 'ManagementSessionTest.memberSwitchRejectsOtherGroupAndStaleRevision', 'ManagementSessionTest.memberTenantAndIdentityRevocationApplyToExistingSessions', 'ManagementSessionTest.platformAllReadSwitchesWithoutMembershipButDoesNotBecomeOperate', 'ManagementSessionTest.unknownAndDisabledIdentityUseSameLoginErrorWithoutSession', 'PasswordCodecTest.independentSaltAndCorrectComparison', 'PasswordCodecTest.invalidPasswordsAreRejectedBeforeDerivation', 'PasswordCodecTest.malformedOrUnboundedParametersAreNeverAccepted', 'ResourceMigrationTest.committedResourceDdlFailureRetainsFailedLedgerAndSafelyRetries', 'ResourceMigrationTest.exactFiveStepEmptyPlanAndRepeatProvideNoResources', 'ResourceMigrationTest.ownershipConstraintsRejectBothGroupsAndPayloadClaims', 'ResourceMigrationTest.v44DataSurvivesResourceUpgradeWithoutHistoryRewrite'])
W03_HTTP_CASES = set(['controller.view', 'formal.anonymous', 'formal.fake-group', 'formal.legacy-login', 'formal.login', 'formal.logout', 'formal.logout-replay', 'formal.correct-password-locked', 'formal.expired-session', 'role.personal-deny-overrides', 'role.no-school-cross-product', 'role.B-independent-allow', 'formal.untrusted-origin', 'formal.wrong-password', 'member.cas', 'member.create', 'member.disable', 'member.foreign-update', 'member.immediate-revocation', 'member.last-admin', 'member.list', 'member.no-implicit-management', 'organization.A-to-B', 'organization.B-to-A', 'organization.cas', 'organization.clear', 'organization.empty', 'organization.foreign-parent', 'organization.reopen', 'organization.unknown', 'platform.A-not-B', 'platform.B-not-A', 'platform.no-DRILL', 'platform.no-EDIT', 'platform.no-EXPORT', 'platform.select-A', 'platform.select-B', 'platform.view-A', 'platform.view-B', 'reader.no-platform-control', 'reader.revoked', 'resource.A-create', 'resource.A-to-B', 'resource.B-create', 'resource.B-to-A', 'resource.native-envelope-consistent', 'resource.no-rebind', 'resource.unregistered-native'])

W04_REGRESSIONS = set(["ManagementHttpBoundaryTest.w04RoleLifecycleStrictCasAndBothGroupDirections","ManagementHttpBoundaryTest.w04AssignmentPairsReplaceClearRejectCrossGroupAndImmutableRoot","ManagementHttpBoundaryTest.w04DelegatedMemberAndRoleMutationsCannotConferManagement","ManagementHttpBoundaryTest.w04OrganizationDenyRemovalAndLastRoleAdministratorRollBack"])
W04_HTTP_CASES = set(["w04.operator-login","w04.delegate-member","w04.delegate-select","w04.member-indirect-escalation","w04.role-read","w04.role-other-group-read","w04.role-cross-update","w04.role-reverse-cross-update","w04.role-duplicate","w04.second-school","w04.assignment-create","w04.financial-assignment","w04.other-assignment-create","w04.assignment-cross-update","w04.assignment-reverse-cross-update","w04.assignment-reverse-foreign-school","w04.assignment-foreign-member","w04.assignment-reverse-foreign-member","w04.assignment-foreign-role-filter","w04.assignment-reverse-foreign-role-filter","w04.assignment-reverse-foreign-school-filter","w04.assignment-pairs","w04.assignment-duplicate","w04.foreign-school","w04.foreign-role","w04.active-empty","w04.duplicate-school","w04.foreign-page-filter","w04.assignment-update","w04.assignment-cas","w04.assignment-immutable-role","w04.assignment-disable-clear","w04.assignment-read-disabled","w04.delegate-ordinary-role","w04.assignment-indirect-escalation","w04.numeric-role-id","w04.null-page","w04.unknown-role-field","w04.role-create-null-id","w04.old-business-route","w04.assignment-unknown","w04.role-disable","w04.role-cas","w04.disabled-role-active-assignment","w04.operator-no-group","w04.anonymous-role","w04.delegate-still-valid","w04.self-disable-last-admin"])

def require(value, code):
    if not value:
        raise RuntimeError(code)


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
    for name in sorted(set(files.decode().splitlines()) | set(["sdk/api/api-permissions/src/main/java/io/dataease/api/permissions/enterprise/RoleManagementApi.java","core/core-backend/src/main/java/io/dataease/enterprise/management/manage/ManagementPrivilegeGuard.java","core/core-backend/src/main/java/io/dataease/enterprise/management/manage/RoleManagementService.java","core/core-backend/src/main/java/io/dataease/enterprise/management/server/RoleManagementServer.java"])):
        if name == 'core/core-frontend/auto-imports.d.ts' or '/resources/static/' in name:
            continue
        path = SOURCE / name
        require(path.is_file(), 'MISSING_TRACKED_PRODUCT_SOURCE')
        require(path.stat().st_mtime <= JAR.stat().st_mtime, 'PRODUCT_SOURCE_NEWER_THAN_JAR')
        rows.append(name + ':' + digest(path.read_bytes()))
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
        'jarSha256': digest(JAR.read_bytes()), 'sourceSha256': digest('\n'.join(rows).encode()),
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
    commands = [
        ('unit', ['mvn', '-B', '-ntp', '-Dmaven.repo.local=' + str(ROOT / 'm2'),
                  '-f', 'core/core-backend/pom.xml', 'test', '-Pstandalone,enterprise-tests']),
        ('hmac', ['node', 'tools/phase1/login-startup-regression.cjs']),
        ('receiptGuard', ['python3', '-B', '-E', 'tools/phase1/test-delivery-gate.py']),
        ('api', ['node', 'tools/phase1/community-compatibility.cjs']),
        ('database', ['python3', '-E', 'tools/phase1/verify-database-boundary.py']),
        ('foundation', ['python3', '-B', '-E', 'tools/phase1/verify-foundation.py']),
        ('control', ['python3', '-B', '-E', 'tools/phase1/verify-w03-control.py', 'all']),
        ('w04', ['python3', '-B', '-E', 'tools/phase1/verify-w04-roles.py'])
    ]
    results = {}
    for name, cmd in commands:
        with (out / (name + '.log')).open('wb') as stream:
            result = subprocess.run(cmd, cwd=SOURCE, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=900 if name == 'unit' else 180)
        require(result.returncode == 0, 'CHECK_FAILED_' + name.upper())
        results[name] = {'passed': True}
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
    hmac_log = (out / 'hmac.log').read_text()
    require('5 passed' in hmac_log, 'HMAC_CASE_COUNT_MISSING')
    results['hmac']['cases'] = 5
    guard_log = (out / 'receiptGuard.log').read_text()
    match = re.search(r'Ran (\d+) tests', guard_log)
    require(match and int(match.group(1)) >= 40 and '\nOK\n' in guard_log, 'RECEIPT_GUARD_TESTS_MISSING')
    results['receiptGuard']['cases'] = int(match.group(1))
    for name, filename, expected in [('api', 'community-api-results.json', 4),
                                     ('database', 'database-boundary-results.json', 14),
                                     ('foundation', 'foundation-results.json', 22)]:
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
            result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=45)
        content = path.read_text()
        require(result.returncode == 1 and marker in content and not any(word in content for word in
                ['HikariPool', 'Initialized JPA EntityManagerFactory', 'Tomcat started']), 'WRONG_GATE_REFUSAL')
    results['enterpriseRefusal'] = {'passed': True, 'cases': 3}
    require(snapshot() == before, 'RUNTIME_OR_SOURCE_CHANGED_DURING_CHECKS')
    receipt = {'runId': run_id, 'identity': before, 'checks': results}
    (out / 'remote-checks.json').write_text(json.dumps(receipt, indent=2))
    return receipt


def verify_gate(head=None):
    path = ROOT / 'logs/delivery-gate.json'
    require(path.is_file(), 'DELIVERY_RECEIPT_MISSING')
    report = json.loads(path.read_text())
    require(report.get('schemaVersion') == 1 and report.get('passed') is True, 'DELIVERY_NOT_PASSED')
    require(0 <= time.time() - report['finishedUnix'] <= 3600, 'DELIVERY_RECEIPT_EXPIRED')
    require(report['identity'] == snapshot(), 'DELIVERY_SOURCE_OR_RUNTIME_CHANGED')
    if head:
        require(report['identity']['head'] == head, 'PUSH_HEAD_NOT_TESTED')
    run_id = report['runId']
    require(str(uuid.UUID(run_id)) == run_id, 'INVALID_RUN_ID')
    remote = json.loads((ROOT / 'logs' / ('delivery-' + run_id) / 'remote-checks.json').read_text())
    require(remote['identity'] == report['identity'] and remote['checks'] == report['checks'],
            'REMOTE_CHECK_RECEIPT_MISMATCH')
    for name, minimum in [('unit', sum(UNIT_SUITES.values())), ('hmac', 5), ('api', 4), ('database', 14), ('enterpriseRefusal', 3), ('receiptGuard', 40), ('foundation', 22), ('control', 80), ('w04', len(W04_HTTP_CASES))]:
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
