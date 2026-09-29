import { spawnSync } from 'node:child_process'

const checks = [
  {
    name: 'Java 21',
    command: 'java',
    args: ['-version'],
    matches: output => /version "21(?:\.|\")/.test(output)
  },
  {
    name: 'Maven 3.9.x',
    command: 'mvn',
    args: ['-version'],
    matches: output => /Apache Maven 3\.9\./.test(output)
  },
  {
    name: 'Docker Compose',
    command: 'docker',
    args: ['compose', 'version'],
    matches: output => /Docker Compose version/i.test(output)
  }
]

let failed = false
for (const check of checks) {
  const result = spawnSync(`${check.command} ${check.args.join(' ')}`, { encoding: 'utf8', shell: true })
  const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`
  const passed = result.status === 0 && check.matches(output)
  console.log(`${passed ? 'OK' : 'MISSING'} ${check.name}`)
  if (!passed) failed = true
}

if (failed) {
  console.error('Build/runtime verification requires the missing tools above.')
  process.exitCode = 1
}
