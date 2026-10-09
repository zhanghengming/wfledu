"""Receipt rejection tests; use synthetic metadata only, without server access."""
import copy
import importlib.util
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('login-test-context.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DeliveryGateTest(unittest.TestCase):
    def setUp(self):
        self.tool_patch = patch.object(gate, 'TOOLS', Path(__file__).parent)
        self.tool_patch.start()
        self.addCleanup(self.tool_patch.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.identity = {'head': 'synthetic-head', 'jarSha256': 'synthetic-jar', 'controlRuntime': {'pid': 123}}
        self.run = '12345678-1234-1234-1234-123456789abc'
        checks = {name: {'passed': True, 'cases': count} for name, count in
                  [('unit', sum(gate.UNIT_SUITES.values())), ('hmac', 5), ('api', 4), ('database', 14), ('enterpriseRefusal', 3), ('receiptGuard', 60), ('foundation', 25), ('control', 80), ('w04', len(gate.W04_HTTP_CASES)), ('storage', len(gate.STORAGE_CASES)), ('resource', len(gate.RESOURCE_CASES))]}
        checks['unit']['schemaRegressions'] = sorted(gate.SCHEMA_REGRESSIONS)
        checks['unit']['jpaRegressions'] = sorted(gate.JPA_REGRESSIONS)
        checks['unit']['mappingRegressions'] = sorted(gate.MAPPING_REGRESSIONS)
        checks['unit']['organizationRegressions'] = sorted(gate.ORGANIZATION_REGRESSIONS)
        checks['unit']['evolutionRegressions'] = sorted(gate.EVOLUTION_REGRESSIONS)
        checks['unit']['auditRegressions'] = sorted(gate.AUDIT_REGRESSIONS)
        checks['unit']['w03Regressions'] = sorted(gate.W03_REGRESSIONS)
        checks['unit']['w04Regressions'] = sorted(gate.W04_REGRESSIONS)
        checks['unit']['generatedRegressions'] = sorted(gate.GENERATED_REGRESSIONS)
        checks['unit']['grantStorageRegressions'] = sorted(gate.GRANT_STORAGE_REGRESSIONS)
        checks['unit']['idempotencyStorageRegressions'] = sorted(gate.IDEMPOTENCY_STORAGE_REGRESSIONS)
        checks['resource'].update(requiredCases=sorted(gate.RESOURCE_CASES), identity=self.identity, runId=self.run)
        checks['storage'].update(requiredCases=sorted(gate.STORAGE_CASES), identity=self.identity, runId=self.run)
        checks['w04'].update(requiredCases=sorted(gate.W04_HTTP_CASES), head='synthetic-head', jarSha256='synthetic-jar', controlRuntime={'pid': 123})
        checks['control'].update(requiredCases=sorted(gate.W03_HTTP_CASES), pid=123, jarSha256='synthetic-jar', head='synthetic-head')
        cases = [{'id': kind + '.' + action, 'status': 'passed'}
                 for kind in ['desktop', 'mobile']
                 for action in ['initialization', 'wrong-password', 'login', 'reload']]
        self.report = {
            'schemaVersion': 1, 'passed': True, 'finishedUnix': 999, 'runId': self.run,
            'identity': self.identity, 'checks': checks,
            'protocol': {'schemaVersion': 1, 'passed': True, 'sources': gate.protocol_sources(),
                         'cases': [{'id': name, 'status': 'passed'} for name in sorted(gate.PROTOCOL_CASES)]},
            'browser': {'passed': True, 'runId': self.run, 'identity': self.identity,
                        'fault': None, 'cases': cases, 'apiErrors': [], 'pageErrors': [],
                        'assetErrors': [], 'networkErrors': []},
            'negativeControls': {key: True for key in ['unexpected-404', 'loading-mask', 'mobile-submit-blocked']}
        }
        out = self.root / 'logs' / ('delivery-' + self.run)
        out.mkdir(parents=True)
        (out / 'remote-checks.json').write_text(json.dumps(
            {'identity': self.identity, 'checks': checks}))

        self.storage = {'schemaVersion': 1, 'runId': self.run, 'passed': True, 'state': 'PASSED',
                        'identity': self.identity,
                        'cases': [{'id': name, 'status': 'passed'} for name in sorted(gate.STORAGE_CASES)]}
        (out / 'w04-storage-results.json').write_text(json.dumps(self.storage))

        self.resource = {'schemaVersion': 1, 'runId': self.run, 'passed': True, 'prebuild': False,
                         'identity': self.identity,
                         'cases': [{'id': name, 'status': 'passed'} for name in sorted(gate.RESOURCE_CASES)]}
        (out / 'resource-verification-results.json').write_text(json.dumps(self.resource))

    def tearDown(self):
        self.temp.cleanup()

    def verify(self, report):
        (self.root / 'logs/delivery-gate.json').write_text(json.dumps(report))
        with patch.object(gate, 'ROOT', self.root), patch.object(gate, 'snapshot', return_value=self.identity), \
                patch.object(gate.time, 'time', return_value=1000):
            return gate.verify_gate('synthetic-head')

    def rejected(self, mutate, code):
        report = copy.deepcopy(self.report)
        mutate(report)
        with self.assertRaisesRegex(RuntimeError, code):
            self.verify(report)

    def test_complete_current_receipt_accepted(self):
        self.assertTrue(self.verify(self.report)['passed'])

    def test_library_summaries_cannot_turn_checks_into_array(self):
        self.rejected(lambda report: report.update(checks=[None, None, None, report['checks']]),
                      'INVALID_CHECKS_ROOT')

    def test_missing_protocol_regressions_rejected(self):
        self.rejected(lambda report: report.pop('protocol'), 'PROTOCOL_CHECKS_MISSING_OR_FAILED')

    def test_database_capacity_requires_isolated_port_and_parallel_headroom(self):
        self.assertEqual(12, gate.validate_database_capacity(13306, 80, 68)['minimumHeadroom'])
        for port, limit, connected, error in [(3306, 80, 39, 'WRONG_CAPACITY_DATABASE'),
                                              (13306, 40, 39, 'INSUFFICIENT_DATABASE_HEADROOM'),
                                              (13306, 80, -1, 'INSUFFICIENT_DATABASE_HEADROOM'),
                                              (13306, 80, 81, 'INSUFFICIENT_DATABASE_HEADROOM')]:
            with self.subTest(port=port, limit=limit, connected=connected):
                with self.assertRaisesRegex(RuntimeError, error):
                    gate.validate_database_capacity(port, limit, connected)

    def test_missing_mobile_cases_rejected(self):
        self.rejected(lambda report: report['browser'].update(cases=report['browser']['cases'][:4]),
                      'BROWSER_CASES_MISSING_OR_FAILED')

    def test_failed_login_rejected(self):
        self.rejected(lambda report: report['browser']['cases'][2].update(status='failed'),
                      'BROWSER_CASES_MISSING_OR_FAILED')

    def test_api_404_cannot_hide_behind_passed_flag(self):
        self.rejected(lambda report: report['browser']['apiErrors'].append({'status': 404}),
                      'BROWSER_ERRORS_PRESENT')

    def test_expired_report_rejected(self):
        self.rejected(lambda report: report.update(finishedUnix=-3000), 'DELIVERY_RECEIPT_EXPIRED')

    def test_previous_jar_or_commit_rejected(self):
        self.rejected(lambda report: report.update(identity={'head': 'old-head'}),
                      'DELIVERY_SOURCE_OR_RUNTIME_CHANGED')

    def test_zero_unit_count_cannot_reuse_old_report(self):
        self.rejected(lambda report: report['checks']['unit'].update(cases=0),
                      'REMOTE_CHECK_RECEIPT_MISMATCH')

    def test_fault_report_cannot_count_as_success(self):
        self.rejected(lambda report: report['browser'].update(fault='unexpected-404'),
                      'BROWSER_IDENTITY_MISMATCH')

    def test_missing_negative_control_rejected(self):
        self.rejected(lambda report: report['negativeControls'].pop('loading-mask'),
                      'NEGATIVE_CONTROLS_MISSING')

    def test_w02_receipt_without_foundation_cannot_pass_w03(self):
        report = copy.deepcopy(self.report)
        report['checks'].pop('foundation')
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)

    def test_enough_tests_cannot_replace_required_schema_regressions(self):
        doc = ET.Element('testsuite', tests='18', failures='0', errors='0', skipped='0')
        for i in range(18):
            ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'SCHEMA_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'FoundationMigrationTest')

    def test_old_39_case_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 39
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)

    def test_missing_schema_regression_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['schemaRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'SCHEMA_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)

    def test_new_running_or_failed_attempt_invalidates_previous_success(self):
        self.rejected(lambda report: report.update(passed=False, state='RUNNING'), 'DELIVERY_NOT_PASSED')

    def test_enough_tests_cannot_replace_required_jpa_regressions(self):
        doc = ET.Element('testsuite', tests='14', failures='0', errors='0', skipped='0')
        for i in range(14):
            ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'JPA_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'EnterpriseJpaIsolationTest')

    def test_missing_jpa_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['jpaRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'JPA_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)

    def test_previous_46_case_receipt_cannot_pass_jpa_work_package(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 46
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)


    def test_enough_tests_cannot_replace_required_mapping_regressions(self):
        doc = ET.Element('testsuite', tests='12', failures='0', errors='0', skipped='0')
        for i in range(12):
            ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'MAPPING_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'FoundationJpaMappingTest')

    def test_missing_mapping_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['mappingRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'MAPPING_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)

    def test_previous_60_case_receipt_cannot_pass_mapping_work_package(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 60
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)


    def test_previous_72_case_receipt_cannot_pass_organization_work_package(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 72
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)

    def test_enough_tests_cannot_replace_required_organization_regressions(self):
        for suite, count in [('OrganizationHierarchyTest', 8), ('OrganizationTransactionTest', 12)]:
            doc = ET.Element('testsuite', tests=str(count), failures='0', errors='0', skipped='0')
            for i in range(count):
                ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
            with self.assertRaisesRegex(RuntimeError, 'ORGANIZATION_REGRESSION_CASES_MISSING'):
                gate.validate_unit_suite(doc, suite)

    def test_missing_organization_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['organizationRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'ORGANIZATION_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)


    def test_previous_92_case_receipt_cannot_pass_evolution_work_package(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 92
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)

    def test_enough_tests_cannot_replace_required_evolution_regressions(self):
        doc = ET.Element('testsuite', tests='12', failures='0', errors='0', skipped='0')
        for i in range(12):
            ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'EVOLUTION_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'EnterpriseMigrationEvolutionTest')

    def test_missing_evolution_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['evolutionRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'EVOLUTION_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)


    def test_previous_104_case_receipt_cannot_pass_formal_audit(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 104
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'):
            self.verify(report)

    def test_enough_tests_cannot_replace_required_audit_names(self):
        doc = ET.Element('testsuite', tests='8', failures='0', errors='0', skipped='0')
        for i in range(8): ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'AUDIT_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'OrganizationAuditTest')

    def test_missing_audit_receipt_rejected_even_if_remote_matches(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['auditRegressions'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'AUDIT_REGRESSION_RECEIPT_MISSING'):
            self.verify(report)


    def test_previous_120_tests_cannot_pass_completed_w03(self):
        report = copy.deepcopy(self.report)
        report['checks']['unit']['cases'] = 120
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_control_cannot_be_omitted_even_with_all_unit_tests(self):
        report = copy.deepcopy(self.report); report['checks'].pop('control')
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_missing_control_case_cannot_be_replaced_by_high_count(self):
        report = copy.deepcopy(self.report); report['checks']['control']['requiredCases'].pop()
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'CONTROL_CASE_RECEIPT_MISSING'): self.verify(report)

    def test_old_control_process_cannot_count_as_current(self):
        report = copy.deepcopy(self.report); report['checks']['control']['pid'] = 1
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))
        with self.assertRaisesRegex(RuntimeError, 'CONTROL_RECEIPT_IDENTITY_MISMATCH'): self.verify(report)

    def test_high_unit_count_cannot_replace_required_http_methods(self):
        doc = ET.Element('testsuite', tests='16', failures='0', errors='0', skipped='0')
        for i in range(16): ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'W03_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'ManagementHttpBoundaryTest')


    def matching_remote(self, report):
        remote = self.root / 'logs' / ('delivery-' + self.run) / 'remote-checks.json'
        remote.write_text(json.dumps({'identity': self.identity, 'checks': report['checks']}))

    def test_w03_182_case_receipt_cannot_pass_w04(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['cases'] = 182
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_w04_cannot_be_omitted(self):
        report = copy.deepcopy(self.report); report['checks'].pop('w04')
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_w04_high_count_cannot_replace_required_http_case(self):
        report = copy.deepcopy(self.report); report['checks']['w04']['requiredCases'].pop()
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'W04_CASE_RECEIPT_MISSING'): self.verify(report)

    def test_w04_old_runtime_cannot_count_as_current(self):
        report = copy.deepcopy(self.report); report['checks']['w04']['controlRuntime']['pid'] = 1
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'W04_RECEIPT_IDENTITY_MISMATCH'): self.verify(report)

    def test_w04_method_names_are_mandatory_and_not_just_counts(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['w04Regressions'].pop()
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'W04_REGRESSION_RECEIPT_MISSING'): self.verify(report)
        doc = ET.Element('testsuite', tests='16', failures='0', errors='0', skipped='0')
        methods = sorted(case.split('.', 1)[1] for case in gate.W03_REGRESSIONS if case.startswith('ManagementHttpBoundaryTest.'))
        for name in methods + ['unrelated_' + str(i) for i in range(4)]:
            ET.SubElement(doc, 'testcase', name=name)
        with self.assertRaisesRegex(RuntimeError, 'W04_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'ManagementHttpBoundaryTest')

    def test_previous_186_tests_cannot_pass_generated_column_unit(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['cases'] = 186
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_generated_names_cannot_be_replaced_by_high_count(self):
        doc = ET.Element('testsuite', tests='10', failures='0', errors='0', skipped='0')
        for i in range(10): ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'GENERATED_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'GeneratedColumnSchemaTest')

    def test_generated_receipt_requires_all_names(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['generatedRegressions'].pop()
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'GENERATED_REGRESSION_RECEIPT_MISSING'): self.verify(report)

    def test_previous_195_tests_cannot_pass_grant_storage(self):
        report=copy.deepcopy(self.report);report['checks']['unit']['cases']=195
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError,'REQUIRED_CHECK_MISSING'):self.verify(report)

    def test_grant_storage_names_cannot_be_replaced_by_high_count(self):
        doc=ET.Element('testsuite',tests='12',failures='0',errors='0',skipped='0')
        for i in range(12):ET.SubElement(doc,'testcase',name='unrelated_'+str(i))
        with self.assertRaisesRegex(RuntimeError,'GRANT_STORAGE_CASES_MISSING'):
            gate.validate_unit_suite(doc,'GrantStorageTest')

    def test_grant_storage_receipt_requires_all_names(self):
        report=copy.deepcopy(self.report);report['checks']['unit']['grantStorageRegressions'].pop()
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError,'GRANT_STORAGE_RECEIPT_MISSING'):self.verify(report)

    def test_previous_207_tests_cannot_pass_cold_initialization_regression(self):
        report=copy.deepcopy(self.report);report['checks']['unit']['cases']=207
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError,'REQUIRED_CHECK_MISSING'):self.verify(report)

    def test_cold_initialization_name_is_mandatory_even_with_enough_tests(self):
        methods=sorted(case.split('.',1)[1] for case in gate.GENERATED_REGRESSIONS
                       if 'coldMigrationEntryPoints' not in case)
        doc=ET.Element('testsuite',tests='10',failures='0',errors='0',skipped='0')
        for name in methods+['unrelated']:ET.SubElement(doc,'testcase',name=name)
        with self.assertRaisesRegex(RuntimeError,'GENERATED_REGRESSION_CASES_MISSING'):
            gate.validate_unit_suite(doc,'GeneratedColumnSchemaTest')

    def test_cold_initialization_receipt_is_required(self):
        report=copy.deepcopy(self.report)
        report['checks']['unit']['generatedRegressions'].remove('GeneratedColumnSchemaTest.coldMigrationEntryPointsNeverReenterCurrentTarget')
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError,'GENERATED_REGRESSION_RECEIPT_MISSING'):self.verify(report)



    def test_previous_208_tests_cannot_pass_idempotency_storage(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['cases'] = 208
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_idempotency_storage_names_cannot_be_replaced_by_high_count(self):
        doc = ET.Element('testsuite', tests='12', failures='0', errors='0', skipped='0')
        for i in range(12): ET.SubElement(doc, 'testcase', name='unrelated_' + str(i))
        with self.assertRaisesRegex(RuntimeError, 'IDEMPOTENCY_STORAGE_CASES_MISSING'):
            gate.validate_unit_suite(doc, 'IdempotencyStorageTest')

    def test_idempotency_storage_receipt_requires_all_names(self):
        report = copy.deepcopy(self.report); report['checks']['unit']['idempotencyStorageRegressions'].pop()
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'IDEMPOTENCY_STORAGE_RECEIPT_MISSING'): self.verify(report)

    def test_missing_storage_acceptance_rejected(self):
        report = copy.deepcopy(self.report); report['checks'].pop('storage')
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_storage_count_cannot_replace_required_case_names(self):
        report = copy.deepcopy(self.report); report['checks']['storage']['requiredCases'].pop()
        report['checks']['storage']['cases'] = 999
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'STORAGE_CASE_RECEIPT_MISSING'): self.verify(report)

    def test_storage_receipt_rejects_other_runtime_or_source(self):
        original = copy.deepcopy(self.storage); original['identity'] = {'head': 'old'}
        (self.root / 'logs' / ('delivery-' + self.run) / 'w04-storage-results.json').write_text(json.dumps(original))
        with self.assertRaisesRegex(RuntimeError, 'STORAGE_RECEIPT_IDENTITY_MISMATCH'): self.verify(self.report)



    def test_delivery_without_resource_protection_cannot_pass(self):
        report = copy.deepcopy(self.report); report['checks'].pop('resource')
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'REQUIRED_CHECK_MISSING'): self.verify(report)

    def test_resource_count_cannot_replace_required_paths(self):
        report = copy.deepcopy(self.report); report['checks']['resource']['requiredCases'].pop()
        report['checks']['resource']['cases'] = 999
        self.matching_remote(report)
        with self.assertRaisesRegex(RuntimeError, 'RESOURCE_CASE_RECEIPT_MISSING'): self.verify(report)

    def test_prebuild_resource_probe_is_not_current_delivery_acceptance(self):
        report = copy.deepcopy(self.resource); report['prebuild'] = True
        (self.root / 'logs' / ('delivery-' + self.run) / 'resource-verification-results.json').write_text(json.dumps(report))
        with self.assertRaisesRegex(RuntimeError, 'RESOURCE_NOT_PASSED'): self.verify(self.report)


if __name__ == '__main__':
    unittest.main()
