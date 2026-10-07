const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, item, i, all) => {
  if (i % 2 === 0) pairs.push([item.replace(/^--/, ''), all[i + 1]]);
  return pairs;
}, []));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw Object.assign(new Error(code), { safeCode: code }); };
const requireCase = (value, code) => { if (!value) fail(code); };

async function main() {
  const base = new URL(args['base-url']);
  requireCase(base.protocol === 'http:' && base.hostname === '127.0.0.1' &&
    ['18010', '18110'].includes(base.port) && base.pathname === '/' && !base.search && !base.hash,
  'UNAPPROVED_BROWSER_TARGET');
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  requireCase(input.identity?.environment === 'w02-isolated-community-18100' &&
    input.credential?.username === 'admin' && typeof input.credential.password === 'string', 'INVALID_PRIVATE_INPUT');
  requireCase(/^[0-9a-f-]{36}$/.test(args['run-id']), 'INVALID_RUN_ID');
  const { chromium, devices } = require(path.resolve(args['module-path']));
  const fault = args.fault || null;
  requireCase(!fault || ['unexpected-404', 'loading-mask', 'mobile-submit-blocked'].includes(fault), 'UNKNOWN_FAULT');
  const report = {
    schemaVersion: 1, runId: args['run-id'], identity: input.identity, fault,
    startedUnix: Date.now() / 1000, passed: false, cases: [],
    apiErrors: [], assetErrors: [], pageErrors: [], networkErrors: [], requests: [], assetChecks: []
  };
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const output = path.resolve(args['out-dir']);
  fs.mkdirSync(output, { recursive: true });
  try {
    const kinds = fault === 'mobile-submit-blocked' ? ['mobile'] : fault ? ['desktop'] : ['desktop', 'mobile'];
    for (const kind of kinds) {
      const cases = ['initialization', 'wrong-password', 'login', 'reload'].map(name =>
        ({ id: kind + '.' + name, status: 'not-run' }));
      report.cases.push(...cases);
      let active = cases[0];
      const context = await browser.newContext({ ...(kind === 'mobile' ? devices['Pixel 7'] : {}),
        serviceWorkers: 'block' });
      const page = await context.newPage();
      const pending = [];
      const seen = new Set();
      page.on('pageerror', () => report.pageErrors.push({ kind, code: 'UNCAUGHT_PAGE_ERROR' }));
      page.on('requestfailed', request => {
        const url = new URL(request.url());
        if (url.origin !== base.origin) return;
        // Superseded document navigations may be cancelled; XHR/fetch/script failures remain fatal.
        if (request.resourceType() === 'document' && request.failure()?.errorText === 'net::ERR_ABORTED') return;
        report.networkErrors.push({ kind, path: url.pathname, type: request.resourceType() });
      });
      page.on('response', response => {
        const url = new URL(response.url());
        if (url.origin !== base.origin) return;
        const status = response.status();
        if (url.pathname.startsWith('/de2api/')) {
          report.requests.push({ kind, path: url.pathname, status });
          if (status >= 400) report.apiErrors.push({ kind, path: url.pathname, status });
          if (['/de2api/setting/authentication/status', '/de2api/perSetting/hmac/info'].includes(url.pathname)) {
            report.apiErrors.push({ kind, path: url.pathname, code: 'UNAVAILABLE_OPTIONAL_API_REQUESTED' });
          }
        } else if (status >= 400 && url.pathname !== '/favicon.ico') {
          report.assetErrors.push({ kind, path: url.pathname, status });
        }
        const expected = input.identity.assets[url.pathname];
        if (expected && status === 200) {
          pending.push(response.body().then(body => {
            const actual = hash(body);
            if (actual !== expected) report.assetErrors.push({ kind, path: url.pathname, code: 'ASSET_SHA_MISMATCH' });
            else seen.add(url.pathname);
          }).catch(() => report.assetErrors.push({ kind, path: url.pathname, code: 'ASSET_BODY_UNAVAILABLE' })));
        }
      });
      const healthy = async () => {
        await Promise.all(pending);
        requireCase(report.apiErrors.length === 0, 'API_HTTP_ERROR');
        requireCase(report.pageErrors.length === 0, 'PAGE_JAVASCRIPT_ERROR');
        requireCase(report.assetErrors.length === 0, 'STATIC_ASSET_ERROR');
        requireCase(report.networkErrors.length === 0, 'NETWORK_REQUEST_FAILED');
      };
      try {
        if (fault === 'unexpected-404') {
          await page.route('**/de2api/sysParameter/ui', route =>
            route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":404}' }));
        }
        await page.goto(base.origin + (kind === 'mobile' ? '/mobile.html#/login?redirect=/' : '/#/login?redirect=/workbranch/index'));
        await page.waitForLoadState('networkidle');
        await healthy();
        const user = page.getByRole('textbox', { name: kind === 'mobile' ? '请输入账号' : '账号/邮箱', exact: true });
        const pwd = page.getByRole('textbox', { name: kind === 'mobile' ? '请输入密码' : '密码', exact: true });
        const login = page.getByRole('button', { name: '登录', exact: true });
        await user.waitFor({ state: 'visible', timeout: 10000 });
        requireCase(await user.isEnabled() && await pwd.isEnabled() && await login.isEnabled(), 'LOGIN_CONTROLS_DISABLED');
        if (fault === 'loading-mask') {
          await page.evaluate(() => {
            const mask = document.createElement('div');
            mask.className = 'preheat-container';
            mask.textContent = 'Regression fault: loading';
            mask.style.cssText = 'position:fixed;inset:0;z-index:99999;background:white';
            document.body.append(mask);
          });
        }
        const masks = page.locator('.preheat-container, .platform-login-mask');
        for (const mask of await masks.all()) requireCase(!(await mask.isVisible()), 'LOADING_MASK_VISIBLE');
        const requiredAssets = input.identity.entryAssets[kind];
        requireCase(Array.isArray(requiredAssets) && requiredAssets.length >= 3, 'INVALID_ENTRY_ASSET_MANIFEST');
        requireCase(requiredAssets.every(asset => seen.has(asset)), 'REQUIRED_ASSET_NOT_VERIFIED');
        report.assetChecks.push({ kind, verified: [...seen] });
        await page.screenshot({ path: path.join(output, kind + '-initialized.png') });
        active.status = 'passed';
        active = cases[1];
        await user.fill(input.credential.username);
        const submit = async password => {
          await pwd.fill(password);
          const responsePromise = page.waitForResponse(res =>
            new URL(res.url()).pathname === '/de2api/login/localLogin' && res.request().method() === 'POST',
          { timeout: 8000 }).catch(() => null);
          await login.click();
          const response = await responsePromise;
          requireCase(response !== null, 'LOGIN_REQUEST_NOT_SENT');
          requireCase(response.status() === 200, 'LOGIN_HTTP_ERROR');
          return response.json();
        };
        const rejected = await submit('SyntheticWrong9!');
        requireCase(rejected.code === 40001 && !rejected.data?.token && new URL(page.url()).hash.startsWith('#/login'),
          'WRONG_PASSWORD_NOT_REJECTED');
        await healthy();
        active.status = 'passed';
        active = cases[2];
        if (fault === 'mobile-submit-blocked') {
          await login.evaluate(button => button.addEventListener('click', event => {
            event.preventDefault(); event.stopImmediatePropagation();
          }, { capture: true, once: true }));
        }
        const accepted = await submit(input.credential.password);
        requireCase(accepted.code === 0 && typeof accepted.data?.token === 'string' && !!accepted.data.token,
          'VALID_LOGIN_NOT_ACCEPTED');
        await page.waitForURL(kind === 'mobile' ? '**/mobile.html#/index' : '**/#/workbranch/index', { timeout: 15000 });
        await page.waitForLoadState('networkidle');
        await healthy();
        active.status = 'passed';
        active = cases[3];
        const loggedInHash = new URL(page.url()).hash;
        await page.reload();
        await page.waitForLoadState('networkidle');
        requireCase(new URL(page.url()).hash === loggedInHash && !loggedInHash.startsWith('#/login'), 'SESSION_LOST_AFTER_RELOAD');
        await healthy();
        await page.screenshot({ path: path.join(output, kind + '-reloaded.png') });
        active.status = 'passed';
      } catch (error) {
        // Playwright error messages/call logs can contain filled values. Never persist them.
        active.status = 'failed';
        active.code = error.safeCode || 'BROWSER_EXECUTION_ERROR';
      } finally {
        await context.close();
      }
    }
  } finally {
    await browser.close();
  }
  report.finishedUnix = Date.now() / 1000;
  report.passed = !fault && report.cases.length === 8 && report.cases.every(item => item.status === 'passed');
  fs.writeFileSync(path.join(output, 'browser-results.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ passed: report.passed, cases: report.cases, fault }));
  if (!report.passed) process.exitCode = 1;
}

main().catch(error => {
  console.error('Browser regression rejected: ' + (error.safeCode || 'BROWSER_EXECUTION_ERROR'));
  process.exitCode = 1;
});
