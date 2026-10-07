"""Verify only this task's MySQL 13306 synthetic databases; never load global client defaults."""
import configparser
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RESULTS = []


def client_config(name):
    config = ROOT / f"runtime/conf/{name}-client.cnf"
    if not config.resolve().is_relative_to((ROOT / "runtime/conf").resolve()):
        raise RuntimeError("client config escaped the task directory")
    parsed = configparser.ConfigParser(interpolation=None)
    parsed.read(config)
    client = parsed["client"]
    if name == "root":
        if client["protocol"] != "socket" or Path(client["socket"]).resolve() != (ROOT / "runtime/mysql/mysql.sock").resolve():
            raise RuntimeError("root client config does not point to the task socket")
    else:
        if client["protocol"] != "tcp" or client["host"] != "127.0.0.1" or client.getint("port") != 13306:
            raise RuntimeError("group client config does not point to the isolated TCP port")
        if client["user"] != f"de_phase1_{name}_ro":
            raise RuntimeError("unexpected group query account")
    return f"--defaults-file={config}"


def query(name, sql):
    return subprocess.run(
        ["/usr/bin/mysql", client_config(name), "--batch", "--skip-column-names", "-e", sql],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False
    )


def main():
    port = query("root", "SELECT @@port;")
    if port.returncode != 0 or port.stdout.strip() != "13306":
        raise RuntimeError("server is not the isolated MySQL 13306 instance")
    for own, other, total in (("ga", "gb", "2003.0000"), ("gb", "ga", "18003.0000")):
        good = query(own, f"SELECT SUM(income_amount) FROM de_phase1_{own}.biz_finance_monthly_fact;")
        assert good.returncode == 0 and good.stdout.strip() == total
        RESULTS.append({"case": f"{own} own finance aggregate", "result": "PASS", "sum": total})
        attempts = (
            ("cross read", f"SELECT * FROM de_phase1_{other}.biz_finance_monthly_fact;"),
            ("cross qualified join", f"SELECT COUNT(*) FROM de_phase1_{own}.biz_school_dim a JOIN de_phase1_{other}.biz_school_dim b ON 1=1;"),
            ("own write", f"UPDATE de_phase1_{own}.biz_finance_monthly_fact SET income_amount=0 WHERE id=-1;"),
            ("metadata read", "SELECT * FROM de_phase1_meta.core_datasource LIMIT 1;")
        )
        for label, sql in attempts:
            denied = query(own, sql)
            assert denied.returncode != 0 and ("1142" in denied.stderr or "1044" in denied.stderr), (own, label)
            RESULTS.append({
                "case": f"{own} {label}", "result": "DENIED",
                "mysql_error": "1142" if "1142" in denied.stderr else "1044"
            })
    metadata = query("root", """
SELECT COUNT(*),SUM(TABLE_COMMENT='') FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('de_phase1_ga','de_phase1_gb');
SELECT COUNT(*),SUM(COLUMN_COMMENT='') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('de_phase1_ga','de_phase1_gb');
SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA IN ('de_phase1_ga','de_phase1_gb') AND CONSTRAINT_TYPE='CHECK';
SELECT version,success+0 FROM de_phase1_meta.de_standalone_version WHERE version LIKE '2.%' ORDER BY installed_rank;
""")
    assert metadata.returncode == 0
    rows = metadata.stdout.strip().splitlines()
    assert rows == ["6\t0", "64\t0", "26", "2.40\t1"], rows
    RESULTS.append({
        "case": "reference physical metadata", "result": "PASS",
        "tables": 6, "fields": 64, "checks": 26, "missing_comments": 0
    })
    negative = (
        ("null school", "INSERT INTO de_phase1_ga.biz_school_dim(school_code,school_name,mapping_revision,status,source_updated_at) VALUES(NULL,'synthetic',1,'ACTIVE',NOW(6));", "1048"),
        ("unknown school", "INSERT INTO de_phase1_ga.biz_finance_monthly_fact(id,school_code,stat_month,category_code,currency_code,income_amount,expense_amount,source_ref,source_version,quality_status,source_updated_at) VALUES(999999,'UNKNOWN','2026-10-01','SYNTHETIC','CNY',1,1,'negative-fixture',1,'VALID',NOW(6));", "1452"),
        ("invalid month", "UPDATE de_phase1_ga.biz_finance_monthly_fact SET stat_month='2026-10-02' WHERE id=100001;", "3819")
    )
    for label, sql, expected in negative:
        # Roll back even an unexpectedly accepted DML statement. DDL is never executed here.
        denied = query("root", "START TRANSACTION; " + sql + " ROLLBACK;")
        assert denied.returncode != 0 and expected in denied.stderr, label
        RESULTS.append({"case": label, "result": "DENIED", "mysql_error": expected})
    (ROOT / "logs/database-boundary-results.json").write_text(json.dumps(RESULTS, indent=2))
    print(f"Database checks: {len(RESULTS)} passed; no credentials printed.")


if __name__ == "__main__":
    main()
