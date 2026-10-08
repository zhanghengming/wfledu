"""Real packaged control-plane acceptance. Only the authorized loopback synthetic runtime."""
import hashlib
import configparser
from concurrent.futures import ThreadPoolExecutor
import json
import os
import secrets
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
SOURCE = ROOT / 'source'
HOME = ROOT / 'runtime/w03-control-home'
PREFIX = '/de2api/api/enterprise/v1/'


def main():
    phase = sys.argv[1] if len(sys.argv) == 2 else 'all'
    assert phase in {'group', 'all'}
    private = HOME / 'accounts.json'
    assert private.stat().st_mode & 0o777 == 0o600
    accounts = json.loads(private.read_text())
    database = accounts['database']
    assert accounts['syntheticOnly'] is True
    import re
    assert re.fullmatch(r'de_phase1_w03_control_[a-f0-9]{12}', database)
    pid = int((HOME / 'app.pid').read_text())
    command = [v.decode() for v in (Path('/proc') / str(pid) / 'cmdline').read_bytes().split(b'\0') if v]
    assert command == json.loads((HOME / 'arguments.json').read_text())
    assert '--server.port=18120' in command and not any('bootstrap-file=' in v for v in command)
    assert (Path('/proc') / str(pid)).stat().st_uid == os.getuid()
    run = uuid.uuid4().hex
    cases = []
    client_file = ROOT / 'runtime/conf/w03-client.cnf'
    assert client_file.stat().st_mode & 0o777 == 0o600
    configuration = configparser.ConfigParser(interpolation=None)
    configuration.read(client_file)
    client = configuration['client']
    assert client['host'] == '127.0.0.1' and client['port'] == '13306' and client['protocol'] == 'tcp'
    assert client['user'] == 'de_phase1_w03_test'
    def sql(statement):
        result = subprocess.run(['/usr/bin/mysql', '--defaults-file=' + str(client_file),
                                 '--batch', '--skip-column-names', database], input=statement, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('Synthetic fixture SQL failed; credentials and SQL not printed')
        return result.stdout.strip()
    assert sql('SELECT marker FROM w03_test_owner;') == 'synthetic-only-retain-no-drop'
    def post(case, route, body, token=None, expected=0, headers=None):
        actual_headers = {'Content-Type': 'application/json'}
        if token: actual_headers['Authorization'] = 'Bearer ' + token
        if headers: actual_headers.update(headers)
        request = urllib.request.Request('http://127.0.0.1:18120' + (route if route.startswith('/') else PREFIX + route),
                                         data=json.dumps(body).encode(), headers=actual_headers)
        try: response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as failure: response = failure
        with response:
            result = json.load(response)
            assert response.headers.get('Cache-Control') == 'no-store', (case, 'unsafe cache header')
            assert result.get('code') == expected, (case, response.status, result.get('code'))
            if expected: assert not result.get('data'), (case, 'rejected request disclosed data')
            cases.append({'id': case, 'code': expected, 'httpStatus': response.status, 'status': 'passed'})
        return result.get('data')
    operator = accounts['operator']
    root_login = post('formal.login', 'auth/login', {'username': operator['username'], 'password': operator['password']})
    assert root_login['mustReset'] is False
    root = root_login['credential']
    post('formal.wrong-password', 'auth/login', {'username': operator['username'], 'password': secrets.token_urlsafe(24)}, expected=20002)
    post('formal.anonymous', 'context/current', {}, expected=20001)
    post('formal.legacy-login', '/de2api/login/localLogin', {}, expected=70001)
    post('formal.fake-group', 'context/current', {}, root, 70001, {'X-Tenant-Id': '10'})
    post('formal.untrusted-origin', 'context/current', {}, root, 70001, {'Origin': 'https://untrusted.invalid'})
    accounts['lastRun'] = {'id': run, 'phase': phase, 'groups': [], 'users': []}
    def persist():
        private.write_text(json.dumps(accounts)); os.chmod(private, 0o600)
    def user(label):
        username = 'w03_' + label + '_' + run[:12]
        initial, password = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
        created = post(label + '.user-create', 'users/create', {'username': username, 'displayName': label, 'temporaryPassword': initial}, root)
        assert isinstance(created['id'], str) and created['id'].isdigit()
        identity = {'id': created['id'], 'username': username, 'password': password, 'initialPassword': initial}
        accounts['lastRun']['users'].append(identity); persist()
        first = post(label + '.first-login', 'auth/login', {'username': username, 'password': initial})
        assert first['mustReset'] is True
        token = first['credential']
        post(label + '.must-reset', 'context/current', {}, token, 70001)
        post(label + '.reset', 'auth/password', {'previousPassword': initial, 'newPassword': password}, token)
        post(label + '.old-token', 'context/current', {}, token, 20001)
        logged = post(label + '.login', 'auth/login', {'username': username, 'password': password})
        assert logged['mustReset'] is False
        return identity, logged['credential']
    def group(label):
        identity, token = user(label)
        created = post(label + '.tenant-create', 'tenants/create', {'code': 'W03_' + label + '_' + run[:12], 'name': 'Synthetic ' + label, 'administratorUserId': identity['id']}, root)
        group = {'tenantId': created['id'], 'memberId': created['administratorMemberId'], 'userId': identity['id']}
        accounts['lastRun']['groups'].append(group); persist()
        post(label + '.select', 'context/switch', {'tenantId': group['tenantId'], 'expectedVersion': '1'}, token)
        school = post(label + '.school-create', 'organizations/save', {'mode': 'CREATE', 'kind': 'SCHOOL', 'name': 'Synthetic school ' + label, 'schoolCode': label + '_' + run, 'status': 'ACTIVE'}, token)
        group['schoolId'] = school['id']; persist()
        mapped = post(label + '.school-read', 'organizations/school', {'id': school['id']}, token)
        assert mapped['tenantId'] == group['tenantId']
        return group, token
    a, ta = group('A'); b, tb = group('B')
    post('organization.A-to-B', 'organizations/school', {'id': b['schoolId']}, ta, 70002)
    post('organization.B-to-A', 'organizations/school', {'id': a['schoolId']}, tb, 70002)
    post('organization.unknown', 'organizations/school', {'id': '999'}, ta, 70002)
    post('organization.empty', 'organizations/school', {'id': ''}, ta, 10001)
    post('organization.foreign-parent', 'organizations/save', {'mode': 'CREATE', 'kind': 'DEPARTMENT', 'name': 'Foreign', 'parentId': b['schoolId'], 'status': 'ACTIVE'}, ta, 70002)
    parent = post('organization.parent', 'organizations/save', {'mode': 'CREATE', 'kind': 'DEPARTMENT', 'name': 'Parent', 'status': 'ACTIVE'}, ta)
    child = post('organization.child', 'organizations/save', {'mode': 'CREATE', 'kind': 'DEPARTMENT', 'name': 'Child', 'parentId': parent['id'], 'status': 'ACTIVE'}, ta)
    change = {'mode': 'UPDATE', 'id': child['id'], 'expectedVersion': '1', 'kind': 'DEPARTMENT', 'name': 'Cleared parent', 'parentId': None, 'status': 'ACTIVE'}
    post('organization.clear', 'organizations/save', change, ta)
    post('organization.cas', 'organizations/save', change, ta, 50002)
    page = post('organization.reopen', 'organizations/page', {}, ta)
    found = next(row for row in page['records'] if row['id'] == child['id'])
    assert found['parentId'] is None and found['version'] == '2'
    person, tp = user('member')
    member = post('member.create', 'members/save', {'mode': 'CREATE', 'userId': person['id'], 'status': 'ACTIVE', 'organizationIds': [a['schoolId']]}, ta)
    post('member.list', 'members/page', {}, ta)
    post('member.select', 'context/switch', {'tenantId': a['tenantId'], 'expectedVersion': '1'}, tp)
    post('member.no-implicit-management', 'members/page', {}, tp, 70001)
    post('member.other-group-select', 'context/switch', {'tenantId': b['tenantId'], 'expectedVersion': '2'}, tp, 70001)
    disable = {'mode': 'UPDATE', 'id': member['id'], 'expectedVersion': '1', 'userId': person['id'], 'status': 'DISABLED', 'organizationIds': []}
    post('member.disable', 'members/save', disable, ta)
    post('member.cas', 'members/save', disable, ta, 50002)
    post('member.immediate-revocation', 'context/current', {}, tp, 70001)
    post('member.foreign-update', 'members/save', {'mode': 'UPDATE', 'id': b['memberId'], 'expectedVersion': '1', 'userId': b['userId'], 'status': 'DISABLED', 'organizationIds': []}, ta, 70002)
    post('member.last-admin', 'members/save', {'mode': 'UPDATE', 'id': a['memberId'], 'expectedVersion': '1', 'userId': a['userId'], 'status': 'DISABLED', 'organizationIds': []}, ta, 70001)
    for i in range(3):
        post('thread.A-denial-' + str(i), 'organizations/school', {'id': b['schoolId']}, ta, 70002)
        own = post('thread.B-own-' + str(i), 'organizations/school', {'id': b['schoolId']}, tb)
        assert own['tenantId'] == b['tenantId']
    post('platform.group-list', 'tenants/page', {}, root)
    if phase == 'all':
        # Typed role fixtures retain assignment-school pairs; public assignment editing belongs to W04.
        actor, role_token = user('roles')
        role_members = []
        for offset, (group, admin) in enumerate([(a, ta), (b, tb)]):
            member = post('role.member-' + str(offset), 'members/save', {'mode': 'CREATE', 'userId': actor['id'], 'status': 'ACTIVE', 'organizationIds': []}, admin)
            role_members.append(member)
            key = str(int(actor['id']) + offset)
            sql('INSERT INTO de_ent_role(id,tenant_id,code,name,status) VALUES(' + key + ',' + group['tenantId'] + ",'SyntheticRole','Synthetic role','ACTIVE');"
                + 'INSERT INTO de_ent_role_assignment(id,tenant_id,member_id,role_id,status) VALUES(' + key + ',' + group['tenantId'] + ',' + member['id'] + ',' + key + ",'ACTIVE');"
                + 'INSERT INTO de_ent_assignment_school(id,tenant_id,assignment_id,school_id) VALUES(' + key + ',' + group['tenantId'] + ',' + key + ',' + group['schoolId'] + ');'
                + 'INSERT INTO de_ent_subject(id,tenant_id,subject_type,role_id) VALUES(' + key + ',' + group['tenantId'] + ",'ROLE'," + key + ');'
                + 'INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES(' + key + ',' + group['tenantId'] + ',' + key + ",'MANAGE_ORGANIZATIONS','ALLOW','ACTIVE');")
        post('role.select-A', 'context/switch', {'tenantId': a['tenantId'], 'expectedVersion': '1'}, role_token)
        post('role.A-allow', 'organizations/page', {}, role_token)
        deny_key = str(int(actor['id']) + 2)
        sql('INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) SELECT ' + deny_key + ',tenant_id,id,\'MANAGE_ORGANIZATIONS\',\'DENY\',\'ACTIVE\' FROM de_ent_subject WHERE tenant_id=' + a['tenantId'] + ' AND member_id=' + role_members[0]['id'] + ';')
        post('role.personal-deny-overrides', 'organizations/page', {}, role_token, 70001)
        sql('UPDATE de_ent_admin_grant SET status=\'DISABLED\' WHERE id=' + deny_key + ';')
        post('role.allow-restored', 'organizations/page', {}, role_token)
        # Disabling this assignment's school cannot borrow the other group's active school.
        post('role.disable-own-school', 'organizations/save', {'mode': 'UPDATE', 'id': a['schoolId'], 'expectedVersion': '1', 'kind': 'SCHOOL', 'name': 'Synthetic disabled', 'schoolCode': 'A_' + run, 'status': 'DISABLED'}, ta)
        post('role.no-school-cross-product', 'organizations/page', {}, role_token, 70001)
        post('role.select-B', 'context/switch', {'tenantId': b['tenantId'], 'expectedVersion': '2'}, role_token)
        post('role.B-independent-allow', 'organizations/page', {}, role_token)
        probe, probe_token = user('lockprobe')
        with ThreadPoolExecutor(max_workers=5) as pool:
            calls = [pool.submit(post, 'formal.lock-failure-' + str(i), 'auth/login',
                                 {'username': probe['username'], 'password': secrets.token_urlsafe(24)}, expected=20002)
                     for i in range(5)]
            for call in calls: call.result(timeout=30)
        assert sql('SELECT failed_attempts FROM de_ent_user_credential WHERE user_id=' + probe['id'] + ';') == '5'
        post('formal.correct-password-locked', 'auth/login', {'username': probe['username'], 'password': probe['password']}, expected=20002)
        expiring, expiring_token = user('expiryprobe')
        sql("UPDATE de_ent_login_session SET created_at=UTC_TIMESTAMP(6)-INTERVAL 8 HOUR,last_seen_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,idle_expires_at=UTC_TIMESTAMP(6)-INTERVAL 2 SECOND,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=" + expiring['id'] + " AND status='ACTIVE';")
        post('formal.expired-session', 'context/current', {}, expiring_token, 20001)
        ra = post('resource.A-create', 'resources/create', {'name': 'Synthetic A'}, ta)
        rb = post('resource.B-create', 'resources/create', {'name': 'Synthetic B'}, tb)
        read_a = {'id': ra['id'], 'action': 'VIEW'}; read_b = {'id': rb['id'], 'action': 'VIEW'}
        post('resource.A-to-B', 'resources/read', read_b, ta, 70002)
        post('resource.B-to-A', 'resources/read', read_a, tb, 70002)
        post('resource.manage-is-not-view', 'resources/read', read_a, ta, 70001)
        post('platform.select-A', 'context/switch', {'tenantId': a['tenantId'], 'expectedVersion': '1'}, root)
        post('platform.view-A', 'resources/read', read_a, root)
        post('platform.A-not-B', 'resources/read', read_b, root, 70002)
        post('platform.switch-conflict', 'context/switch', {'tenantId': b['tenantId'], 'expectedVersion': '1'}, root, 50002)
        for action in ['EDIT', 'EXPORT', 'DRILL']:
            post('platform.no-' + action, 'resources/read', {'id': ra['id'], 'action': action}, root, 70001)
        post('platform.no-implicit-create', 'resources/create', {'name': 'Denied'}, root, 70001)
        post('platform.select-B', 'context/switch', {'tenantId': b['tenantId'], 'expectedVersion': '2'}, root)
        post('platform.view-B', 'resources/read', read_b, root)
        post('platform.B-not-A', 'resources/read', read_a, root, 70002)
        post('resource.unknown', 'resources/read', {'id': '999', 'action': 'VIEW'}, root, 70002)
        unregistered = str(int(actor['id']) + 3)
        sql('INSERT INTO data_visualization_info(id,name,org_id,node_type,type,source,delete_flag) VALUES(' + unregistered + ",'Synthetic unregistered'," + b['tenantId'] + ",'panel','dashboard','w03-negative-fixture',0);")
        post('resource.unregistered-native', 'resources/read', {'id': unregistered, 'action': 'VIEW'}, root, 70002)
        post('resource.no-rebind', 'resources/create', {'id': ra['id'], 'name': 'Forbidden', 'tenantId': b['tenantId']}, tb, 10001)
        for route in ['/de2api/dataVisualization/findById/' + ra['id'], '/de2api/chartData/getData', '/de2api/exportCenter/export', '/de2api/share/validate']:
            post('legacy.' + route.split('/')[2], route, {}, root, 70001)
        for label, qualification, expected in [('reader', 'GROUP_READ_ALL', 0), ('controller', 'PLATFORM_OPERATE', 70001)]:
            identity, token = user(label)
            # Explicit synthetic qualification fixture. Authentication always uses the real login API.
            sql('INSERT INTO de_ent_platform_qualification(id,user_id,qualification,status) VALUES(' + identity['id'] + ',' + identity['id'] + ", '" + qualification + "','ACTIVE');")
            post(label + '.select', 'context/switch', {'tenantId': a['tenantId'], 'expectedVersion': '1'}, token)
            post(label + '.view', 'resources/read', read_a, token, expected)
            post(label + '.no-edit', 'resources/read', {'id': ra['id'], 'action': 'EDIT'}, token, 70001)
            if label == 'reader':
                post('reader.no-platform-control', 'users/create', {'username': 'denied_' + run, 'displayName': 'Denied', 'temporaryPassword': secrets.token_urlsafe(24)}, token, 70001)
                sql('UPDATE de_ent_platform_qualification SET status=\'DISABLED\' WHERE id=' + identity['id'] + ';')
                post('reader.revoked', 'resources/read', read_a, token, 70001)
        for resource, group in [(ra, a), (rb, b)]:
            assert sql('SELECT COUNT(*) FROM data_visualization_info v JOIN de_ent_resource r ON v.id=r.id WHERE v.id=' + resource['id'] + ' AND v.org_id=r.tenant_id AND r.tenant_id=' + group['tenantId'] + ';') == '1'
        cases.append({'id': 'resource.native-envelope-consistent', 'status': 'passed'})
    post('formal.logout', 'auth/logout', {}, root)
    post('formal.logout-replay', 'context/current', {}, root, 20001)
    jar = SOURCE / 'core/core-backend/target/CoreApplication.jar'
    relevant = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '--', 'core/core-backend/src', 'sdk', 'tools/phase1'], cwd=SOURCE, text=True).splitlines()
    digest = hashlib.sha256()
    for name in sorted(set(relevant)):
        file = SOURCE / name
        if file.is_file(): digest.update(name.encode() + b'\0' + hashlib.sha256(file.read_bytes()).digest())
    report = {'passed': True, 'phase': phase, 'runId': run, 'pid': pid, 'database': database,
              'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'sourceSha256': digest.hexdigest(),
              'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip(),
              'cases': cases, 'privateAccountRef': str(private)}
    output = ROOT / ('logs/w03-control-' + phase + '-results.json')
    output.write_text(json.dumps(report, indent=2))
    print('W03 packaged ' + phase + ' HTTP acceptance: ' + str(len(cases)) + ' passed; evidence ' + str(output))


if __name__ == '__main__':
    main()
