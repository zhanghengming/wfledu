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
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.identity = {'head': 'synthetic-head', 'jarSha256': 'synthetic-jar'}
        self.run = '12345678-1234-1234-1234-123456789abc'
        checks = {name: {'passed': True, 'cases': count} for name, count in
                  [('unit', sum(gate.UNIT_SUITES.values())), ('hmac', 5), ('api', 4), ('database', 14), ('enterpriseRefusal', 3), ('receiptGuard', 23), ('foundation', 10)]}
        checks['unit']['schemaRegressions'] = sorted(gate.SCHEMA_REGRESSIONS)
        checks['unit']['jpaRegressions'] = sorted(gate.JPA_REGRESSIONS)
        checks['unit']['mappingRegressions'] = sorted(gate.MAPPING_REGRESSIONS)
        checks['unit']['organizationRegressions'] = sorted(gate.ORGANIZATION_REGRESSIONS)
        cases = [{'id': kind + '.' + action, 'status': 'passed'}
                 for kind in ['desktop', 'mobile']
                 for action in ['initialization', 'wrong-password', 'login', 'reload']]
        self.report = {
            'schemaVersion': 1, 'passed': True, 'finishedUnix': 999, 'runId': self.run,
            'identity': self.identity, 'checks': checks,
            'browser': {'passed': True, 'runId': self.run, 'identity': self.identity,
                        'fault': None, 'cases': cases, 'apiErrors': [], 'pageErrors': [],
                        'assetErrors': [], 'networkErrors': []},
            'negativeControls': {key: True for key in ['unexpected-404', 'loading-mask', 'mobile-submit-blocked']}
        }
        out = self.root / 'logs' / ('delivery-' + self.run)
        out.mkdir(parents=True)
        (out / 'remote-checks.json').write_text(json.dumps(
            {'identity': self.identity, 'checks': checks}))

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


if __name__ == '__main__':
    unittest.main()
