#!/usr/bin/env python3
"""Reproduce W04 step-3 storage acceptance in the isolated task lab only."""
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj

gate = module('storage_gate', 'login-test-context.py')
boundary = module('storage_boundary', 'verify-database-boundary.py')
ROOT, SOURCE = gate.ROOT, gate.SOURCE
EXPECTED_ROOT = Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
TABLES = ['de_ent_user', 'de_ent_tenant', 'de_ent_tenant_member', 'de_ent_org',
          'de_ent_audit_event', 'de_ent_org_member', 'de_ent_role',
          'de_ent_role_assignment', 'de_ent_assignment_school', 'de_ent_subject',
          'de_ent_admin_grant', 'de_ent_user_credential', 'de_ent_platform_qualification',
          'de_ent_login_session', 'de_ent_resource', 'de_ent_grant',
          'de_ent_grant_school', 'de_ent_idempotency']


def authorization_state(database):
    # Full content fingerprints, not row counts: existing synthetic policies must survive.
    gate.require(re.fullmatch(r'de_phase1_w03_control_[0-9a-f]{12}', database) is not None,
                 'WRONG_PRESERVATION_DATABASE')
    marker = boundary.query('root', 'SELECT marker FROM ' + database + '.w03_test_owner;')
    gate.require(marker.returncode == 0 and marker.stdout.strip() == 'synthetic-only-retain-no-drop',
                 'STORAGE_OWNER_MARKER_MISMATCH')
    state = {}
    for table in ['de_ent_grant', 'de_ent_grant_school', 'de_ent_idempotency']:
        columns = boundary.query('root', "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" +
                                 database + "' AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION;")
        names = columns.stdout.strip().splitlines()
        gate.require(columns.returncode == 0 and names and 'id' in names and
                     all(re.fullmatch(r'[a-z][a-z0-9_]*', name) for name in names), 'INVALID_STORAGE_COLUMNS')
        expression = ','.join("COALESCE(HEX(`" + name + "`),'<NULL>')" for name in names)
        rows = boundary.query('root', "SELECT SHA2(CONCAT_WS(':'," + expression + "),256) FROM " +
                              database + '.' + table + ' ORDER BY id;')
        gate.require(rows.returncode == 0, 'STORAGE_PRESERVATION_READ_FAILED')
        state[table] = {'rows': len(rows.stdout.splitlines()),
                        'sha256': hashlib.sha256(rows.stdout.encode('utf-8')).hexdigest()}
    return state


def main():
    gate.require(ROOT == EXPECTED_ROOT and HERE == SOURCE / 'tools/phase1', 'WRONG_STORAGE_TASK_DIRECTORY')
    before = gate.snapshot()
    run_id = str(uuid.uuid4())
    out = ROOT / 'logs' / ('w04-storage-' + run_id)
    out.mkdir(exist_ok=False)
    receipt = ROOT / 'logs/w04-storage-results.json'
    report = {'schemaVersion': 1, 'runId': run_id, 'passed': False, 'state': 'RUNNING',
              'identity': before, 'cases': [], 'finishedUnix': time.time()}
    receipt.write_text(json.dumps(report))
    (ROOT / 'logs/delivery-gate.json').write_text(json.dumps({
        'schemaVersion': 1, 'passed': False, 'state': 'STORAGE_ACCEPTANCE_RUNNING',
        'runId': run_id, 'identity': before, 'finishedUnix': time.time()}))
    try:
        manifest_path = ROOT / 'runtime/w03-control-home/accounts.json'
        gate.require(manifest_path.stat().st_mode & 0o777 == 0o600, 'PRIVATE_MANIFEST_PERMISSIONS')
        manifest = json.loads(manifest_path.read_text())
        database = manifest.get('database')
        gate.require(manifest.get('syntheticOnly') is True and isinstance(database, str)
                     and re.fullmatch(r'de_phase1_w03_control_[0-9a-f]{12}', database) is not None,
                     'NOT_SYNTHETIC_STORAGE_DATABASE')
        env = os.environ.copy()
        env['JAVA_HOME'] = '/usr/lib/jvm/java-21'
        env['PATH'] = env['JAVA_HOME'] + '/bin:/opt/maven/bin:' + env['PATH']
        original_policies = authorization_state(database)
        start = time.time()
        command = ['mvn', '-B', '-ntp', '-Dmaven.repo.local=' + str(ROOT / 'm2'),
                   '-f', 'core/core-backend/pom.xml', 'test', '-Pstandalone,enterprise-tests',
                   '-Dtest=GeneratedColumnSchemaTest,GrantStorageTest,IdempotencyStorageTest']
        resource = module('storage_resource', 'resource-job.py')
        with (out / 'mysql-jpa.log').open('wb') as log:
            result = resource.run('storage-unit', command, SOURCE, stdout=log, timeout=900)
        gate.require(result.get('passed') is True, 'STORAGE_MYSQL_JPA_FAILED')
        for suite in ['GeneratedColumnSchemaTest', 'GrantStorageTest', 'IdempotencyStorageTest']:
            paths = list((SOURCE / 'core/core-backend/target/surefire-reports').glob('TEST-*.' + suite + '.xml'))
            gate.require(len(paths) == 1 and paths[0].stat().st_mtime >= start, 'STALE_STORAGE_JUNIT_REPORT')
            doc = ET.parse(paths[0]).getroot()
            gate.validate_unit_suite(doc, suite)
            observed = {suite + '.' + item.attrib['name'] for item in doc.findall('testcase')}
            expected = {name for name in gate.STORAGE_CASES if name.startswith(suite + '.')}
            gate.require(observed == expected, 'STORAGE_CASE_NAMES_CHANGED')
            report['cases'].extend({'id': name, 'status': 'passed'} for name in sorted(observed))
            (out / paths[0].name).write_bytes(paths[0].read_bytes())

        def check(name, sql, expected):
            result = boundary.query('root', sql)
            gate.require(result.returncode == 0 and result.stdout.strip() == expected,
                         'STORAGE_RUNTIME_CHECK_FAILED_' + name)
            report['cases'].append({'id': name, 'status': 'passed'})

        check('storage.port', 'SELECT @@port;', '13306')
        names = ','.join("'" + name + "'" for name in TABLES)
        for label, db in [('compatibility', 'de_phase1_meta'), ('management', database)]:
            # Only the formal management fixture has a private owner marker.
            if label == 'management':
                marker = boundary.query('root', 'SELECT marker FROM ' + db + '.w03_test_owner;')
                gate.require(marker.returncode == 0 and marker.stdout.strip() == 'synthetic-only-retain-no-drop',
                             'STORAGE_OWNER_MARKER_MISMATCH')
            where = "TABLE_SCHEMA='" + db + "' AND TABLE_NAME IN (" + names + ')'
            check('storage.' + label + '.tables',
                  "SELECT COUNT(*),SUM(ENGINE<>'InnoDB'),SUM(TABLE_COLLATION<>'utf8mb4_0900_bin'),SUM(TABLE_COMMENT='') FROM information_schema.TABLES WHERE " + where,
                  '18\t0\t0\t0')
            check('storage.' + label + '.columns',
                  "SELECT COUNT(*),SUM(COLUMN_COMMENT='') FROM information_schema.COLUMNS WHERE " + where,
                  '208\t0')
            check('storage.' + label + '.generated',
                  "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'<NULL>'),EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + db +
                  "' AND ((TABLE_NAME='de_ent_grant' AND COLUMN_NAME='resource_key') OR (TABLE_NAME='de_ent_idempotency' AND COLUMN_NAME='principal_key')) ORDER BY TABLE_NAME;",
                  'de_ent_grant\tresource_key\tbigint\tYES\t<NULL>\tSTORED GENERATED\tcoalesce(`resource_id`,0)\nde_ent_idempotency\tprincipal_key\tbigint\tYES\t<NULL>\tSTORED GENERATED\tcoalesce(`user_id`,`app_id`)')
            if label == 'compatibility':
                check('storage.compatibility.empty',
                      'SELECT (SELECT COUNT(*) FROM ' + db + '.de_ent_grant),(SELECT COUNT(*) FROM ' +
                      db + '.de_ent_grant_school),(SELECT COUNT(*) FROM ' + db + '.de_ent_idempotency);',
                      '0\t0\t0')
            else:
                current_policies = authorization_state(database)
                gate.require(current_policies == original_policies, 'STORAGE_EXISTING_POLICIES_CHANGED')
                report['preservedPolicies'] = current_policies
                report['cases'].append({'id': 'storage.management.preserved', 'status': 'passed'})
            check('storage.' + label + '.history',
                  "SELECT version,success+0 FROM " + db + ".de_standalone_version WHERE version LIKE '4.%' ORDER BY installed_rank;",
                  '\n'.join('4.' + str(n) + '\t1' for n in range(1, 8)))
        gate.require({case['id'] for case in report['cases']} == gate.STORAGE_CASES, 'STORAGE_CASES_MISSING')
        gate.require(gate.snapshot() == before, 'STORAGE_SOURCE_OR_RUNTIME_CHANGED')
        report.update(passed=True, state='PASSED', finishedUnix=time.time())
    except Exception as error:
        report.update(state='FAILED', finishedUnix=time.time(), errorType=type(error).__name__)
        receipt.write_text(json.dumps(report, indent=2))
        (out / 'results.json').write_text(json.dumps(report, indent=2))
        raise
    receipt.write_text(json.dumps(report, indent=2))
    (out / 'results.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'passed': True, 'runId': run_id, 'cases': len(report['cases']),
                      'head': before['head'], 'jarSha256': before['jarSha256']}))


if __name__ == '__main__':
    main()
