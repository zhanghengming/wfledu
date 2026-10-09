"""Read-only checks of the opt-in foundation in the authorized task metadata database."""
import json
from pathlib import Path
import importlib.util

spec = importlib.util.spec_from_file_location('boundary', Path(__file__).with_name('verify-database-boundary.py'))
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)
ROOT = boundary.ROOT
TABLES = ['de_ent_user', 'de_ent_tenant', 'de_ent_tenant_member', 'de_ent_org', 'de_ent_audit_event', 'de_ent_org_member', 'de_ent_role', 'de_ent_role_assignment', 'de_ent_assignment_school', 'de_ent_subject', 'de_ent_admin_grant', 'de_ent_user_credential', 'de_ent_platform_qualification', 'de_ent_login_session', 'de_ent_resource', 'de_ent_grant', 'de_ent_grant_school', 'de_ent_idempotency']


def main():
    if ROOT != Path('/home/data_dev_zhm/dataease-phase1-test/w02-security'):
        raise RuntimeError('WRONG_FOUNDATION_TASK_DIRECTORY')
    rows = []

    def check(name, sql, expected):
        result = boundary.query('root', sql)
        if result.returncode != 0 or result.stdout.strip() != expected:
            raise RuntimeError('FOUNDATION_CHECK_FAILED_' + name)
        rows.append({'case': name, 'result': 'PASS'})

    check('isolated-port', 'SELECT @@port;', '13306')
    names = ','.join("'" + name + "'" for name in TABLES)
    where = "TABLE_SCHEMA='de_phase1_meta' AND TABLE_NAME IN (" + names + ')'
    check('tables', "SELECT COUNT(*),SUM(ENGINE<>'InnoDB'),SUM(TABLE_COLLATION<>'utf8mb4_0900_bin'),SUM(TABLE_COMMENT='') FROM information_schema.TABLES WHERE " + where, '18\t0\t0\t0')
    check('columns', "SELECT COUNT(*),SUM(COLUMN_COMMENT=''),SUM(COLLATION_NAME IS NOT NULL AND COLLATION_NAME<>'utf8mb4_0900_bin') FROM information_schema.COLUMNS WHERE " + where, '208\t0\t0')
    check('checks', "SELECT COUNT(*),SUM(ENFORCED<>'YES') FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_TYPE='CHECK' AND " + where, '64\t0')
    check('foreign-keys', "SELECT COUNT(*),SUM(DELETE_RULE<>'RESTRICT' OR UPDATE_RULE<>'RESTRICT' OR UNIQUE_CONSTRAINT_SCHEMA<>'de_phase1_meta') FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='de_phase1_meta' AND TABLE_NAME IN (" + names + ')', '70\t0')
    check('indexes', "SELECT COUNT(DISTINCT TABLE_NAME,INDEX_NAME) FROM information_schema.STATISTICS WHERE " + where, '107')
    check('versions', "SELECT version,success+0 FROM de_phase1_meta.de_standalone_version WHERE version IN ('2.40','4.1','4.2','4.3','4.4','4.5','4.6','4.7') ORDER BY installed_rank;", '2.40\t1\n4.1\t1\n4.2\t1\n4.3\t1\n4.4\t1\n4.5\t1\n4.6\t1\n4.7\t1')
    for table in TABLES:
        check('no-auto-provision-' + table, 'SELECT COUNT(*) FROM de_phase1_meta.' + table, '0')
    (ROOT / 'logs/foundation-results.json').write_text(json.dumps(rows, indent=2))
    print('Foundation checks: 25 passed; read-only, no credentials printed.')


if __name__ == '__main__':
    main()
