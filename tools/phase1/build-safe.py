"""Sequential full build through the bounded job executor. Never resumes an old result."""
import hashlib
import importlib.util
import json
import subprocess
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('job', HERE / 'resource-job.py')
job = importlib.util.module_from_spec(spec)
spec.loader.exec_module(job)


input_spec = importlib.util.spec_from_file_location('product_inputs', HERE / 'product-inputs.py')
inputs = importlib.util.module_from_spec(input_spec)
input_spec.loader.exec_module(inputs)


def source_digest():
    return inputs.source_digest(job.SOURCE)


def main():
    if HERE != job.SOURCE / 'tools/phase1':
        raise RuntimeError('WRONG_SAFE_BUILD_DIRECTORY')
    branch = subprocess.check_output(['git', 'branch', '--show-current'], cwd=job.SOURCE, text=True).strip()
    if branch != 'codex/phase1-security-baseline':
        raise RuntimeError('WRONG_SAFE_BUILD_BRANCH')
    proof = json.loads((job.ROOT / 'logs/resource-verification-results.json').read_text())
    if not proof.get('passed') or not proof.get('prebuild') or time.time() - proof['finishedUnix'] > 3600:
        raise RuntimeError('FRESH_PREBUILD_RESOURCE_PROOF_REQUIRED')
    for pidfile in [job.ROOT / 'runtime/app.pid', job.ROOT / 'runtime/w03-control-home/app.pid']:
        proc = Path('/proc') / pidfile.read_text().strip()
        if proc.exists() and (proc / 'stat').read_text().split(') ')[1].split()[0] != 'Z':
            raise RuntimeError('PAUSE_DEDICATED_APPLICATIONS_BEFORE_BUILD')
    run_id = str(uuid.uuid4())
    out = job.ROOT / 'logs' / ('safe-build-' + run_id)
    out.mkdir()
    record = {'schemaVersion': 1, 'runId': run_id, 'state': 'RUNNING', 'passed': False,
              'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=job.SOURCE, text=True).strip(),
              'branch': branch, 'sourceSha256': source_digest(), 'startedUnix': time.time(),
              'prebuildRunId': proof['runId'], 'stages': {}}
    latest = job.ROOT / 'logs/safe-build-results.json'
    job.atomic(latest, record)
    mvn = ['mvn', '-B', '-ntp', '-Dmaven.repo.local=' + str(job.ROOT / 'm2')]
    commands = [('sdk', mvn + ['install', '-DskipTests'], job.SOURCE),
                ('frontend', ['npm', 'run', 'build:distributed:bounded'], job.SOURCE / 'core/core-frontend'),
                ('backend', mvn + ['-f', 'core/core-backend/pom.xml', 'package', '-Pstandalone,enterprise-tests'], job.SOURCE)]
    try:
        for name, command, cwd in commands:
            if source_digest() != record['sourceSha256']:
                raise RuntimeError('SOURCE_CHANGED_DURING_BUILD')
            with (out / (name + '.log')).open('wb') as log:
                result = job.run(name, command, cwd, stdout=log, timeout=1200)
            record['stages'][name] = result
            job.atomic(latest, record)
            job.atomic(out / 'results.json', record)
            if not result['passed']:
                raise RuntimeError('BOUNDED_BUILD_STAGE_FAILED_' + name.upper())
            print(name + ': 0', flush=True)
        if source_digest() != record['sourceSha256']:
            raise RuntimeError('SOURCE_CHANGED_DURING_BUILD')
        jar = job.SOURCE / 'core/core-backend/target/CoreApplication.jar'
        record.update(state='PASSED', passed=True, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    except BaseException as error:
        record.update(state='FAILED', errorType=type(error).__name__, errorCode=str(error))
        raise
    finally:
        record['finishedUnix'] = time.time()
        job.atomic(latest, record)
        job.atomic(out / 'results.json', record)
    print(json.dumps({'passed': True, 'runId': run_id, 'jarSha256': record['jarSha256']}))


if __name__ == '__main__':
    main()
