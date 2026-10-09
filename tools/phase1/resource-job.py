"""One bounded task job. This module never changes existing service configuration."""
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
SOURCE = ROOT / 'source'
HIGH = 3584 * 1024 * 1024
MAXIMUM = 4096 * 1024 * 1024
MIN_AVAILABLE = 6144 * 1024 * 1024
FRONTEND_HIGH = 7680 * 1024 * 1024
FRONTEND_MAXIMUM = 8192 * 1024 * 1024
FRONTEND_MIN_AVAILABLE = 10240 * 1024 * 1024
STOP_AVAILABLE = 2048 * 1024 * 1024
CRITICAL_AVAILABLE = 1024 * 1024 * 1024


def available():
    return next(int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines()
                if line.startswith('MemAvailable:'))


def capacity(memory, disk, minimum=MIN_AVAILABLE):
    if memory < minimum:
        raise RuntimeError('JOB_INSUFFICIENT_MEMORY')
    if disk < 2 * 1024 ** 3:
        raise RuntimeError('JOB_INSUFFICIENT_DISK')


def unit_name(value):
    if not re.fullmatch(r'de-phase1-job-[0-9a-f]{32}\.service', value):
        raise RuntimeError('WRONG_TASK_UNIT')
    return value


def systemctl(*args):
    return subprocess.run(['sudo', '-n', 'systemctl', *args],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20)


def stop(unit):
    unit_name(unit)
    result = systemctl('stop', unit)
    # A unit may have finished and been collected already.
    if result.returncode and 'not loaded' not in result.stderr and 'not found' not in result.stderr:
        raise RuntimeError('TASK_GROUP_STOP_FAILED')


def atomic(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, indent=2))
    os.replace(temp, path)


def busy_processes():
    observed = []
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            args = [item.decode() for item in (proc / 'cmdline').read_bytes().split(b'\0') if item]
            if not args or proc.stat().st_uid != os.getuid():
                continue
            cwd = (proc / 'cwd').resolve()
            node = Path(args[0]).name == 'node' and cwd.is_relative_to(ROOT)
            java_build = 'org.codehaus.plexus.classworlds.launcher.Launcher' in args or any(
                Path(item).name.startswith('surefirebooter-') and item.endswith('.jar') for item in args)
            if node or java_build:
                observed.append(int(proc.name))
        except (OSError, UnicodeError):
            continue
    return observed


def run(kind, command, cwd, stdout=None, timeout=1200, probe_limit=None):
    if Path(__file__).resolve() != SOURCE / 'tools/phase1/resource-job.py':
        raise RuntimeError('WRONG_JOB_TOOL_DIRECTORY')
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,40}', kind):
        raise RuntimeError('INVALID_JOB_KIND')
    cwd = Path(cwd).resolve()
    if cwd not in {ROOT, SOURCE, SOURCE / 'core/core-frontend'}:
        raise RuntimeError('WRONG_JOB_WORKING_DIRECTORY')
    if not command or command[0] not in {'mvn', 'npm', '/usr/lib/jvm/java-21/bin/java', sys.executable}:
        raise RuntimeError('WRONG_JOB_EXECUTABLE')
    if not 1 <= timeout <= 1800:
        raise RuntimeError('WRONG_JOB_TIMEOUT')
    if probe_limit is not None and (not kind.startswith('probe-') or probe_limit not in {64, 128}):
        raise RuntimeError('WRONG_PROBE_LIMIT')
    frontend = kind in {'frontend', 'probe-frontend-limits'}
    budget_high = FRONTEND_HIGH if frontend else HIGH
    budget_maximum = FRONTEND_MAXIMUM if frontend else MAXIMUM
    budget_minimum = FRONTEND_MIN_AVAILABLE if kind == 'frontend' else MIN_AVAILABLE
    job = uuid.uuid4().hex
    out = ROOT / 'logs' / ('resource-job-' + job)
    out.mkdir(exist_ok=False)
    unit = 'de-phase1-job-' + job + '.service'
    record = {'schemaVersion': 1, 'runId': job, 'kind': kind, 'unit': unit,
              'startedUnix': time.time(), 'state': 'STARTING', 'passed': False,
              'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip(),
              'toolSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'limits': {'memoryHigh': budget_high, 'memoryMax': budget_maximum,
                         'minimumAvailable': budget_minimum, 'cpuQuota': '200%'},
              'log': str(out / 'command.log'), 'samples': []}
    if stdout is not None:
        record['log'] = str(getattr(stdout, 'name', '<external-output>'))
    receipt = out / 'result.json'
    atomic(receipt, record)
    runner = None
    stream = None
    cgroup = None
    with (ROOT / 'runtime/build-test.lock').open('a+') as lock:
        try:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('JOB_BUSY')
            capacity(available(), shutil.disk_usage(ROOT).free, budget_minimum)
            busy = busy_processes()
            if busy:
                raise RuntimeError('UNMANAGED_HEAVY_JOB_PRESENT')
            high = budget_high if probe_limit is None else probe_limit * 1024 ** 2
            maximum = budget_maximum if probe_limit is None else probe_limit * 1024 ** 2
            record['limits'].update(memoryHigh=high, memoryMax=maximum)
            search_path = '/usr/lib/jvm/java-21/bin:/opt/maven/bin:' + os.environ['PATH']
            executable = shutil.which(command[0], path=search_path)
            if executable is None:
                raise RuntimeError('JOB_EXECUTABLE_NOT_FOUND')
            command = [executable, *command[1:]]
            cmd = ['sudo', '-n', 'systemd-run', '--quiet', '--wait', '--pipe', '--service-type=exec',
                   '--unit=' + unit, '--uid=' + str(os.getuid()), '--gid=' + str(os.getgid()),
                   '--working-directory=' + str(cwd), '--property=MemoryAccounting=yes',
                   '--property=MemoryHigh=' + str(high), '--property=MemoryMax=' + str(maximum),
                   '--property=CPUQuota=200%', '--property=TasksMax=512', '--property=LimitCORE=0',
                   '--property=KillMode=control-group', '--property=OOMPolicy=kill',
                   '--property=TimeoutStopSec=15', '--property=RuntimeMaxSec=' + str(timeout),
                   '--property=ExecStopPost=/bin/sleep 2',
                   '--setenv=JAVA_HOME=/usr/lib/jvm/java-21',
                   '--setenv=PATH=/usr/lib/jvm/java-21/bin:/opt/maven/bin:' + os.environ['PATH'],
                   '--setenv=HOME=/home/data_dev_zhm',
                   '--setenv=NODE_OPTIONS=--max_old_space_size=' + ('7168' if frontend else '3072'),
                   '--setenv=MAVEN_OPTS=-Xmx1024m',
                   '--setenv=JAVA_TOOL_OPTIONS=-Xmx1536m', *command]
            stream = (out / 'command.log').open('wb') if stdout is None else stdout
            runner = subprocess.Popen(cmd, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            record.update(state='RUNNING', runnerPid=runner.pid)
            atomic(receipt, record)
            started = time.monotonic()
            low_since = None
            while runner.poll() is None:
                if cgroup is None:
                    query = systemctl('show', unit, '--property=ControlGroup', '--value')
                    relative = query.stdout.strip()
                    if relative:
                        candidate = Path('/sys/fs/cgroup') / relative.lstrip('/')
                        if candidate.name != unit or not candidate.resolve().is_relative_to(Path('/sys/fs/cgroup')):
                            raise RuntimeError('WRONG_TASK_CGROUP')
                        cgroup = candidate
                memory = available()
                sample = {'unix': time.time(), 'available': memory}
                if cgroup is not None and cgroup.exists():
                    for field in ['memory.current', 'memory.peak', 'memory.max', 'memory.high', 'memory.events', 'cpu.max', 'cgroup.events']:
                        path = cgroup / field
                        if path.exists():
                            sample[field] = path.read_text().strip()
                    if sample.get('memory.max') != str(maximum) or sample.get('memory.high') != str(high):
                        raise RuntimeError('TASK_MEMORY_LIMIT_NOT_EFFECTIVE')
                record['samples'].append(sample)
                atomic(receipt, record)
                if memory < CRITICAL_AVAILABLE:
                    raise RuntimeError('JOB_HOST_MEMORY_CRITICAL')
                if memory < STOP_AVAILABLE:
                    low_since = low_since or time.monotonic()
                    if time.monotonic() - low_since >= 30:
                        raise RuntimeError('JOB_HOST_MEMORY_LOW')
                else:
                    low_since = None
                if time.monotonic() - started > timeout + 25:
                    raise RuntimeError('JOB_TIMEOUT_WATCHDOG')
                time.sleep(0.5)
            if runner.returncode == 0 and not any('memory.max' in sample for sample in record['samples']):
                raise RuntimeError('NO_EFFECTIVE_CGROUP_PROOF')
            record['exitCode'] = runner.returncode
            record['service'] = systemctl('show', unit, '--property=Result', '--property=ExecMainStatus',
                                          '--property=MemoryPeak', '--property=ControlGroup').stdout.strip()
            record.update(state='PASSED' if runner.returncode == 0 else 'FAILED', passed=runner.returncode == 0)
        except BaseException as error:
            record.update(state='CANCELLED' if isinstance(error, KeyboardInterrupt) else 'FAILED',
                          errorType=type(error).__name__, errorCode=str(error))
            raise
        finally:
            if runner is not None:
                try:
                    stop(unit)
                finally:
                    if runner.poll() is None:
                        try:
                            runner.wait(timeout=20)
                        except subprocess.TimeoutExpired:
                            runner.terminate()
                            runner.wait(timeout=5)
                    if cgroup is not None and cgroup.exists():
                        active = (cgroup / 'cgroup.events').read_text()
                        if 'populated 1' in active:
                            record.update(passed=False, state='FAILED', cleanupError='TASK_CGROUP_NOT_EMPTY')
                    systemctl('reset-failed', unit)
            if stream is not None and stdout is None:
                stream.close()
            record['finishedUnix'] = time.time()
            atomic(receipt, record)
            fcntl.flock(lock, fcntl.LOCK_UN)
    return record


def main():
    kind = sys.argv[1]
    mvn = ['mvn', '-B', '-ntp', '-Dmaven.repo.local=' + str(ROOT / 'm2')]
    presets = {
        'sdk': (mvn + ['install', '-DskipTests'], SOURCE),
        'frontend': (['npm', 'run', 'build:distributed:bounded'], SOURCE / 'core/core-frontend'),
        'backend': (mvn + ['-f', 'core/core-backend/pom.xml', 'package', '-Pstandalone,enterprise-tests'], SOURCE),
        'unit': (mvn + ['-f', 'core/core-backend/pom.xml', 'test', '-Pstandalone,enterprise-tests'], SOURCE)}
    if kind not in presets:
        raise RuntimeError('UNKNOWN_JOB_PRESET')
    command, cwd = presets[kind]
    record = run(kind, command, cwd)
    print(json.dumps({'kind': kind, 'runId': record['runId'], 'passed': record['passed'],
                      'exitCode': record.get('exitCode'),
                      'receipt': str(ROOT / 'logs' / ('resource-job-' + record['runId']) / 'result.json')}), flush=True)
    if not record['passed']:
        raise SystemExit(record.get('exitCode') or 1)


if __name__ == '__main__':
    main()
