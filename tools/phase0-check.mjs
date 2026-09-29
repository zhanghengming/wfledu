import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'

const root = resolve(import.meta.dirname, '..')
const required = [
  'PHASE0.md',
  'AGENTS.md',
  'LICENSE',
  'pom.xml',
  'core/pom.xml',
  'core/core-backend/pom.xml',
  'core/core-frontend/package.json',
  'project-docs/phase0/verification.md',
  'project-docs/phase0/source-audit.md',
  'project-docs/phase0/baseline.json',
  'project-docs/phase0/review-checklist.md',
  'project-docs/architecture/overview.md',
  'project-docs/architecture/module-boundaries.md',
  'project-docs/architecture/tenant.md',
  'project-docs/architecture/permission.md',
  'project-docs/architecture/embedding.md',
  'project-docs/architecture/deployment.md',
  'project-docs/architecture/threat-model.md',
  'project-docs/development/standards.md',
  'project-docs/development/api.md',
  'project-docs/development/database.md',
  'project-docs/development/configuration.md',
  'project-docs/development/migration.md',
  'project-docs/development/testing.md',
  'project-docs/development/observability.md',
  'project-docs/development/upstream.md',
  'project-docs/development/build-and-run.md',
  'project-docs/adr/README.md',
  'deploy/dev/.env.example',
  'deploy/dev/compose.yaml',
  'deploy/dev/application.local.example.yml',
  'tools/phase0-env-check.mjs'
]

const failures = []
for (const relative of required) {
  if (!existsSync(resolve(root, relative))) failures.push(`missing: ${relative}`)
}

const baseline = JSON.parse(readFileSync(resolve(root, 'project-docs/phase0/baseline.json'), 'utf8'))
if (!/^v\d+\.\d+\.\d+$/.test(baseline.release)) failures.push('invalid release')
if (!/^[0-9a-f]{40}$/.test(baseline.commit)) failures.push('invalid commit SHA')
try {
  execFileSync('git', ['cat-file', '-e', `${baseline.commit}^{commit}`], { cwd: root, stdio: 'ignore' })
} catch {
  failures.push(`baseline commit not present: ${baseline.commit}`)
}

const markdown = required.filter(path => path.endsWith('.md'))
for (const relative of markdown) {
  const source = readFileSync(resolve(root, relative), 'utf8')
  for (const match of source.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
    const target = match[1].split('#')[0]
    if (!target || /^(?:https?:|mailto:|#)/.test(target)) continue
    if (!existsSync(resolve(root, dirname(relative), decodeURIComponent(target)))) {
      failures.push(`broken link in ${relative}: ${target}`)
    }
  }
}

const example = readFileSync(resolve(root, 'deploy/dev/.env.example'), 'utf8')
for (const name of ['DE_DEV_MYSQL_ROOT_PASSWORD', 'DE_DEV_MYSQL_PASSWORD', 'DE_DEV_REDIS_PASSWORD', 'DE_DEV_ADMIN_PASSWORD', 'DE_DEV_SUBSTITULE_PATH']) {
  if (!new RegExp(`^${name}=CHANGE_ME_`, 'm').test(example)) failures.push(`unsafe or missing placeholder: ${name}`)
}

if (failures.length) {
  for (const failure of failures) console.error(failure)
  process.exitCode = 1
} else {
  console.log(`Phase 0 static checks passed: ${required.length} required files, ${markdown.length} Markdown files, baseline ${baseline.release}`)
}
