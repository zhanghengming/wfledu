"""Safe real-cgroup regression. Allocations stay inside a 64 MiB probe group."""
import importlib.util
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('resource_job', HERE / 'resource-job.py')
job = importlib.util.module_from_spec(spec)
spec.loader.exec_module(job)
CASES = {'resource.limits', 'resource.mutual-exclusion', 'resource.capacity-refusal',
         'resource.timeout-descendants', 'resource.cancel-descendants',
         'resource.oom-contained', 'resource.protected-services'}


def protected():
    return sorted(line for line in subprocess.check_output(['ss', '-ltnpH'], text=True).splitlines()
                  if line.split()[3].rsplit(':', 1)[-1] in {'3306', '6379', '8100', '13306', '16379'})


def wait_for(test, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = test()
        if value:
            return value
        time.sleep(0.1)
    raise RuntimeError('RESOURCE_PROBE_DID_NOT_REACH_EXPECTED_STATE')


def dead(path):
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    if len(rows) != 3:
        raise RuntimeError('DESCENDANT_FIXTURE_INCOMPLETE')
    for row in rows:
        proc = Path('/proc') / str(row['pid'])
        if proc.exists():
            stat = (proc / 'stat').read_text().split(') ')[1].split()
            if stat[19] == row['ticks'] and stat[0] != 'Z':
                raise RuntimeError('TASK_DESCENDANT_SURVIVED')
    return rows


def descendants(path):
    record = ("import os,json,time;from pathlib import Path;"
              "p=Path('/proc')/str(os.getpid());"
              "row={'pid':os.getpid(),'ticks':(p/'stat').read_text().split(') ')[1].split()[19]};"
              "f=Path(" + repr(str(path)) + ").open('a');"
              "f.write(json.dumps(row)+'\\n');f.close();")
    leaf = record + "time.sleep(120)"
    child = "import subprocess,sys;" + record + "subprocess.Popen([sys.executable,'-c'," + repr(leaf) + "]);time.sleep(120)"
    parent = "import subprocess,sys;" + record + "subprocess.Popen([sys.executable,'-c'," + repr(child) + "]);time.sleep(120)"
    return [sys.executable, '-B', '-c', parent]


def launch(kind, command, log, timeout):
    program = ("import importlib.util;from pathlib import Path;"
               "s=importlib.util.spec_from_file_location('r'," + repr(str(HERE / 'resource-job.py')) + ");"
               "r=importlib.util.module_from_spec(s);s.loader.exec_module(r);"
               "result=r.run(" + repr(kind) + "," + repr(command) + ",r.SOURCE,timeout=" + str(timeout) + ");"
               "raise SystemExit(0 if result['passed'] else 1)")
    return subprocess.Popen([sys.executable, '-B', '-c', program], cwd=job.SOURCE,
                            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)


def running(kind):
    for path in (job.ROOT / 'logs').glob('resource-job-*/result.json'):
        try:
            row = json.loads(path.read_text())
            if row.get('kind') == kind and row.get('state') == 'RUNNING':
                return path
        except (OSError, ValueError):
            continue
    return None


def main():
    prebuild = sys.argv[1:] == ['--prebuild']
    if sys.argv[1:] not in ([], ['--prebuild']):
        raise RuntimeError('INVALID_RESOURCE_VERIFICATION_MODE')
    if HERE != job.SOURCE / 'tools/phase1':
        raise RuntimeError('WRONG_RESOURCE_VERIFICATION_DIRECTORY')
    identity = None
    if not prebuild:
        spec = importlib.util.spec_from_file_location('context', HERE / 'login-test-context.py')
        context = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(context)
        identity = context.snapshot()
    run_id = str(uuid.uuid4())
    out = job.ROOT / 'logs' / ('resource-verification-' + run_id)
    out.mkdir()
    before = protected()
    report = {'schemaVersion': 1, 'runId': run_id, 'passed': False, 'prebuild': prebuild,
              'identity': identity, 'startedUnix': time.time(), 'cases': [], 'jobs': []}
    receipt = job.ROOT / 'logs/resource-verification-results.json'
    job.atomic(receipt, report)
    def passed(name):
        report['cases'].append({'id': name, 'status': 'passed'})

    try:
        program = ("from pathlib import Path;import json,time;"
                   "cg=Path('/sys/fs/cgroup')/Path('/proc/self/cgroup').read_text().split('::',1)[1].strip().lstrip('/');"
                   "quota,period=(cg/'cpu.max').read_text().split();"
                   "assert int((cg/'memory.max').read_text())==4294967296;"
                   "assert int((cg/'memory.high').read_text())==3758096384;"
                   "assert int(quota)==2*int(period);time.sleep(2)")
        result = job.run('probe-limits', [sys.executable, '-B', '-c', program], job.SOURCE, timeout=10)
        assert result['passed'] and any('memory.max' in sample for sample in result['samples'])
        report['jobs'].append(result['runId'])
        frontend_program = program.replace('4294967296', str(job.FRONTEND_MAXIMUM)).replace(
            '3758096384', str(job.FRONTEND_HIGH))
        result = job.run('probe-frontend-limits', [sys.executable, '-B', '-c', frontend_program],
                         job.SOURCE, timeout=10)
        assert result['passed'] and any('memory.max' in sample for sample in result['samples'])
        report['jobs'].append(result['runId']); passed('resource.limits')

        for memory, disk, code in [(job.MIN_AVAILABLE - 1, 10 * 1024 ** 3, 'JOB_INSUFFICIENT_MEMORY'),
                                    (job.MIN_AVAILABLE, 0, 'JOB_INSUFFICIENT_DISK')]:
            try:
                job.capacity(memory, disk)
            except RuntimeError as error:
                assert str(error) == code
            else:
                raise RuntimeError('INSUFFICIENT_CAPACITY_WAS_ACCEPTED')
        try:
            job.capacity(job.FRONTEND_MIN_AVAILABLE - 1, 10 * 1024 ** 3,
                         job.FRONTEND_MIN_AVAILABLE)
        except RuntimeError as error:
            assert str(error) == 'JOB_INSUFFICIENT_MEMORY'
        else:
            raise RuntimeError('FRONTEND_CAPACITY_LIMIT_WAS_ACCEPTED')
        try:
            job.unit_name('dataease-dev.service')
        except RuntimeError:
            pass
        else:
            raise RuntimeError('PROTECTED_SERVICE_NAME_ACCEPTED')
        passed('resource.capacity-refusal')

        kind = 'probe-lock-' + uuid.uuid4().hex[:8]
        with (out / 'lock.log').open('wb') as log:
            runner = launch(kind, [sys.executable, '-B', '-c', 'import time;time.sleep(5)'], log, 15)
            try:
                first = wait_for(lambda: running(kind))
                try:
                    job.run('probe-competing', [sys.executable, '-c', 'pass'], job.SOURCE, timeout=5)
                except RuntimeError as error:
                    assert str(error) == 'JOB_BUSY'
                else:
                    raise RuntimeError('SECOND_HEAVY_JOB_WAS_ACCEPTED')
                assert runner.wait(timeout=25) == 0
                report['jobs'].append(json.loads(first.read_text())['runId'])
            finally:
                if runner.poll() is None:
                    runner.send_signal(signal.SIGINT); runner.wait(timeout=25)
        passed('resource.mutual-exclusion')

        path = out / 'timeout-pids.jsonl'
        result = job.run('probe-timeout', descendants(path), job.SOURCE, timeout=3)
        assert not result['passed'] and result['exitCode'] != 0
        report['timeoutPids'] = dead(path); report['jobs'].append(result['runId'])
        passed('resource.timeout-descendants')

        path = out / 'cancel-pids.jsonl'; kind = 'probe-cancel-' + uuid.uuid4().hex[:8]
        with (out / 'cancel.log').open('wb') as log:
            runner = launch(kind, descendants(path), log, 60)
            try:
                first = wait_for(lambda: running(kind))
                wait_for(lambda: path.exists() and len(path.read_text().splitlines()) == 3)
                runner.send_signal(signal.SIGINT)
                assert runner.wait(timeout=30) != 0
                cancelled = json.loads(first.read_text())
                assert cancelled['state'] == 'CANCELLED' and not cancelled['passed']
                report['jobs'].append(cancelled['runId'])
            finally:
                if runner.poll() is None:
                    runner.send_signal(signal.SIGINT); runner.wait(timeout=30)
        report['cancelPids'] = dead(path); passed('resource.cancel-descendants')

        result = job.run('probe-oom', [sys.executable, '-B', '-c', 'x=bytearray(128*1024*1024)'],
                         job.SOURCE, timeout=15, probe_limit=64)
        oom = 'Result=oom-kill' in result.get('service', '') or any(
            any(line.startswith('oom_kill ') and int(line.split()[1]) > 0
                for line in sample.get('memory.events', '').splitlines()) for sample in result['samples'])
        assert not result['passed'] and result['exitCode'] != 0 and oom
        report['jobs'].append(result['runId']); passed('resource.oom-contained')
        assert protected() == before
        passed('resource.protected-services')
        assert {case['id'] for case in report['cases']} == CASES
        if identity is not None:
            assert context.snapshot() == identity
        report['passed'] = True
    except BaseException as error:
        report['errorType'] = type(error).__name__
        raise
    finally:
        report['finishedUnix'] = time.time()
        job.atomic(receipt, report)
        job.atomic(out / 'results.json', report)
    print(json.dumps({'passed': True, 'runId': run_id, 'cases': len(report['cases']),
                      'prebuild': prebuild}))


if __name__ == '__main__':
    main()
