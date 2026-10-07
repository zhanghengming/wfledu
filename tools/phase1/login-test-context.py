#!/usr/bin/env python3
"""Only the dedicated W02 environment. --private stdout must go to a private pipe."""
import argparse
import hashlib
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
              'verify-delivery.ps1', 'pre-push-check.sh', 'test-delivery-gate.py']


def require(value, code):
    if not value:
        raise RuntimeError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


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
    for name in files.decode().splitlines():
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
        'environment': 'w02-isolated-community-18100'
    }


def checks(run_id):
    require(str(uuid.UUID(run_id)) == run_id, 'INVALID_RUN_ID')
    before = snapshot()
    out = ROOT / 'logs' / ('delivery-' + run_id)
    out.mkdir(exist_ok=False)
    env = os.environ.copy()
    env['JAVA_HOME'] = '/usr/lib/jvm/java-21'
    env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
    start = time.time()
    commands = [
        ('unit', ['mvn', '-B', '-ntp', '-Dmaven.repo.local=' + str(ROOT / 'm2'),
                  '-f', 'core/core-backend/pom.xml', 'test', '-Pstandalone,enterprise-tests']),
        ('hmac', ['node', 'tools/phase1/login-startup-regression.cjs']),
        ('receiptGuard', ['python3', '-B', '-E', 'tools/phase1/test-delivery-gate.py']),
        ('api', ['node', 'tools/phase1/community-compatibility.cjs']),
        ('database', ['python3', '-E', 'tools/phase1/verify-database-boundary.py'])
    ]
    results = {}
    for name, cmd in commands:
        with (out / (name + '.log')).open('wb') as stream:
            result = subprocess.run(cmd, cwd=SOURCE, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=180)
        require(result.returncode == 0, 'CHECK_FAILED_' + name.upper())
        results[name] = {'passed': True}
    suites = {'EnterpriseAssemblyGuardTest': 12, 'AccessContextHolderTest': 10}
    total = 0
    for name, minimum in suites.items():
        paths = list((SOURCE / 'core/core-backend/target/surefire-reports').glob('TEST-*.' + name + '.xml'))
        require(len(paths) == 1 and paths[0].stat().st_mtime >= start, 'STALE_OR_MISSING_UNIT_REPORT')
        doc = ET.parse(paths[0]).getroot()
        count = int(doc.attrib['tests'])
        require(count >= minimum and all(int(doc.attrib[k]) == 0 for k in ['errors', 'failures', 'skipped']),
                'UNIT_CASES_NOT_EXECUTED_OR_FAILED')
        total += count
    results['unit']['cases'] = total
    hmac_log = (out / 'hmac.log').read_text()
    require('5 passed' in hmac_log, 'HMAC_CASE_COUNT_MISSING')
    results['hmac']['cases'] = 5
    guard_log = (out / 'receiptGuard.log').read_text()
    match = re.search(r'Ran (\d+) tests', guard_log)
    require(match and int(match.group(1)) >= 9 and '\nOK\n' in guard_log, 'RECEIPT_GUARD_TESTS_MISSING')
    results['receiptGuard']['cases'] = int(match.group(1))
    for name, filename, expected in [('api', 'community-api-results.json', 4),
                                     ('database', 'database-boundary-results.json', 14)]:
        path = ROOT / 'logs' / filename
        require(path.stat().st_mtime >= start, 'STALE_' + name.upper() + '_REPORT')
        body = path.read_bytes()
        data = json.loads(body)
        # Existing tools exit nonzero on failed behavior; additionally reject an empty report.
        count = len(data) if isinstance(data, list) else len(data.get('checks', []))
        require(count == expected, 'WRONG_' + name.upper() + '_CASE_COUNT')
        (out / filename).write_bytes(body)
        results[name]['cases'] = count
    for name, switch, marker in [
            ('missing-services', 'true', 'Enterprise security assembly rejected'),
            ('invalid-switch', 'tru', 'enterprise.enabled must be explicitly true or false')]:
        cmd = [env['JAVA_HOME'] + '/bin/java', '-Duser.home=' + str(ROOT / 'runtime/gate-home'),
               '-jar', str(JAR), '--spring.config.additional-location=file:' + str(
                   ROOT / 'runtime/gate-home/opt/dataease3.0/config/application.yml'),
               '--enterprise.enabled=' + switch]
        path = out / ('gate-' + name + '.log')
        with path.open('wb') as stream:
            result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=45)
        content = path.read_text()
        require(result.returncode == 1 and marker in content and not any(word in content for word in
                ['HikariPool', 'Initialized JPA EntityManagerFactory', 'Tomcat started']), 'WRONG_GATE_REFUSAL')
    results['enterpriseRefusal'] = {'passed': True, 'cases': 2}
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
    for name, minimum in [('unit', 22), ('hmac', 5), ('api', 4), ('database', 14), ('enterpriseRefusal', 2), ('receiptGuard', 9)]:
        item = report['checks'].get(name, {})
        require(item.get('passed') is True and item.get('cases', 0) >= minimum, 'REQUIRED_CHECK_MISSING')
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
