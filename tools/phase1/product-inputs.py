"""Shared content manifest for the dedicated product build and its delivery gate."""
import hashlib
import os
import subprocess
from pathlib import Path

IGNORED_DIRECTORIES = {'target', 'node_modules', 'dist', '.git', '__pycache__'}
GENERATED = {'core/core-frontend/auto-imports.d.ts', 'core/core-frontend/components.d.ts'}
ROOT_BUILD_FILES = {'pom.xml', '.npmrc', '.node-version', '.nvmrc', 'package.json', 'package-lock.json'}


def manifest(source):
    source = Path(source).resolve()
    names = set(subprocess.check_output(
        ['git', 'ls-files', '--', 'core', 'sdk', 'pom.xml', '.mvn', '.npmrc',
         '.node-version', '.nvmrc', 'package.json', 'package-lock.json'],
        cwd=source, text=True).splitlines())
    names.add('pom.xml')
    names.update(name for name in ROOT_BUILD_FILES if (source / name).is_file())
    # Include new sources before Git staging, across all SDK/core modules, not just enterprise packages.
    for root in ['core', 'sdk', '.mvn']:
        for directory, children, files in os.walk(source / root, followlinks=False):
            children[:] = sorted(c for c in children if c not in IGNORED_DIRECTORIES)
            for filename in files:
                path = Path(directory) / filename
                relative = path.relative_to(source)
                in_source = any(part in {'src', 'config', 'public'} for part in relative.parts)
                build_file = filename in {'pom.xml', 'package.json', 'package-lock.json',
                                          'yarn.lock', 'pnpm-lock.yaml', '.npmrc'}
                frontend_entry = relative.parent.as_posix() == 'core/core-frontend' and path.suffix in {'.ts', '.js', '.html', '.json'}
                if root == '.mvn' or build_file or frontend_entry or in_source:
                    names.add(relative.as_posix())
    rows = []
    for name in sorted(names):
        if name in GENERATED or '/resources/static/' in name or any(part in IGNORED_DIRECTORIES for part in Path(name).parts):
            continue
        path = source / name
        if not path.is_file():
            raise RuntimeError('MISSING_PRODUCT_BUILD_INPUT')
        if path.is_symlink() or not path.resolve().is_relative_to(source):
            raise RuntimeError('PRODUCT_BUILD_INPUT_ESCAPES_WORKSPACE')
        rows.append(name + ':' + hashlib.sha256(path.read_bytes()).hexdigest())
    return rows


def source_digest(source):
    return hashlib.sha256('\n'.join(manifest(source)).encode()).hexdigest()
