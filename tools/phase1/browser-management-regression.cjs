const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, key, index, all) => {
  if (index % 2 === 0) pairs.push([key.replace(/^--/, ''), all[index + 1]]);
  return pairs;
}, []));
const requireCase = (value, code) => { if (!value) throw Object.assign(new Error(code), { safeCode: code }); };
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const escape = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
async function main() {
  const base = new URL(args['base-url']);
  requireCase(base.origin === 'http://127.0.0.1:18020' && base.pathname === '/' && !base.search && !base.hash, 'UNAPPROVED_MANAGEMENT_BROWSER_TARGET');
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  requireCase(input.identity?.environment === 'w03-community-18100-control-18120' && input.fixture?.admina && input.fixture?.adminb && input.fixture?.member && /^[a-f0-9]{12}$/.test(input.nonce), 'INVALID_PRIVATE_MANAGEMENT_INPUT');
  const { chromium } = require(path.resolve(args['module-path']));
  const report = { schemaVersion: 1, runId: args['run-id'], identity: input.identity, startedUnix: Date.now() / 1000,
    passed: false, cases: [], requests: [], apiErrors: [], assetErrors: [], pageErrors: [], networkErrors: [], securityErrors: [], assetChecks: [] };
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ serviceWorkers: 'block', locale: 'zh-CN' });
  const page = await context.newPage();
  const pending = [];
  const inFlight = new Set();
  let active = null;
  let allowAbort = false;
  let heldRequest = null;
  const expectedErrors = new Set();
  const fixture = {};
  const prefix = '/de2api/api/enterprise/v1/';
  const enterprise = route => base.origin + '/enterprise.html#' + route;
  await page.exposeBinding('__taskManagementCsp', (_, directive) => {
    report.securityErrors.push({ id: active?.id, directive, code: 'CSP_EXECUTION_BLOCKED' });
  });
  await context.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', event => {
      if (['script-src', 'script-src-elem', 'connect-src'].includes(event.effectiveDirective)) {
        window.__taskManagementCsp(event.effectiveDirective);
      }
    });
  });
  page.on('console', message => {
    if (message.type() !== 'error') return;
    for (const argument of message.args()) pending.push(argument.evaluate(value => value instanceof Error)
      .then(isError => { if (isError) report.pageErrors.push({ id: active?.id, code: 'CAUGHT_PAGE_ERROR' }); }).catch(() => {}));
  });
  page.on('pageerror', () => report.pageErrors.push({ id: active?.id, code: 'UNCAUGHT_PAGE_ERROR' }));
  page.on('request', request => { if (new URL(request.url()).origin === base.origin) inFlight.add(request); });
  page.on('requestfinished', request => inFlight.delete(request));
  page.on('requestfailed', request => {
    inFlight.delete(request);
    const url = new URL(request.url());
    if (url.origin !== base.origin) return;
    if (request.failure()?.errorText === 'net::ERR_ABORTED' && (request.resourceType() === 'document' || allowAbort)) return;
    report.networkErrors.push({ id: active?.id, path: url.pathname, type: request.resourceType() });
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin !== base.origin) return;
    const id = active?.id;
    const status = response.status();
    if (url.pathname.startsWith('/de2api/')) {
      const allowed = new Set(expectedErrors);
      pending.push(response.json().then(body => {
        report.requests.push({ id, path: url.pathname, status, code: body.code });
        if (!url.pathname.startsWith(prefix) || (body.code !== 0 && !allowed.has(body.code))) {
          report.apiErrors.push({ id, path: url.pathname, status, code: body.code });
        }
      }).catch(() => report.apiErrors.push({ id, path: url.pathname, status, code: 'INVALID_API_RESPONSE' })));
    } else {
      if (status >= 400 && url.pathname !== '/favicon.ico') report.assetErrors.push({ id, path: url.pathname, status });
      const expected = input.identity.assets?.[url.pathname];
      if (expected && status === 200) pending.push(response.body().then(data => {
        report.assetChecks.push({ path: url.pathname, passed: hash(data) === expected });
      }).catch(() => report.assetErrors.push({ id, path: url.pathname, code: 'ASSET_UNREADABLE' })));
    }
  });
  const settle = async () => {
    // loadState(networkidle) belongs to navigation; it can already be satisfied for later XHRs.
    const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    for (let attempt = 0; attempt < 1500; attempt++) {
      await frame();
      const busy = await page.locator('.ed-button.is-loading').count();
      if (!busy && [...inFlight].every(request => allowAbort && request === heldRequest)) { await Promise.all(pending); return; }
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    requireCase(false, 'PAGE_REQUESTS_NOT_SETTLED');
  };
  const step = async (id, action, errors = []) => {
    active = { id: 'management.' + id, status: 'running' };
    report.cases.push(active);
    expectedErrors.clear(); errors.forEach(code => expectedErrors.add(code));
    try { await action(); await settle(); active.status = 'passed'; }
    catch (error) { active.status = 'failed'; active.code = error.safeCode || 'UI_FLOW_FAILED'; active.failureKind = error.name; active.failureLines = [...(error.stack || '').matchAll(/browser-management-regression\.cjs:(\d+):/g)].map(m => Number(m[1])); throw error; }
  };
  const submit = async (button, route, expected = 0) => {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === prefix + route && r.request().method() === 'POST');
    const [, received] = await Promise.all([button.click(), response]);
    const result = await received.json();
    requireCase(result.code === expected, 'FORM_RESPONSE_REJECTED');
    return result.data;
  };
  const login = async (user, password, expected = 0) => {
    await page.goto(enterprise('/login'));
    await page.locator('input[name=username]').fill(user);
    await page.locator('input[name=password]').fill(password);
    const result = await submit(page.getByRole('button', { name: '登录', exact: true }), 'auth/login', expected);
    if (expected === 0) {
      if (result.mustReset) await page.locator('input[name=previousPassword]').waitFor();
      else await page.waitForURL(url => url.hash === '#/groups');
    }
    await settle(); return result;
  };
  const logout = async () => {
    await settle();
    await submit(page.getByRole('button', { name: '退出登录', exact: true }), 'auth/logout');
    await page.waitForURL(url => url.hash === '#/login');
    await settle();
  };
  const reset = async user => {
    await login(user.username, user.initialPassword);
    await page.locator('input[name=previousPassword]').fill(user.initialPassword);
    await page.locator('input[name=newPassword]').fill(user.password);
    await page.locator('input[name=confirmPassword]').fill(user.password);
    await submit(page.getByRole('button', { name: '修改密码', exact: true }), 'auth/password');
    await page.getByText('密码已修改，请使用新密码登录', { exact: true }).waitFor();
    await login(user.username, user.password);
    await page.waitForURL(url => url.hash === '#/groups');
  };
  const groupName = name => 'W08-' + name + '-' + input.nonce;
  const row = text => page.locator('.ed-table__row').filter({ hasText: text }).first();
  const enter = async name => {
    await settle();
    await page.waitForURL(url => url.hash === '#/groups');
    await page.locator('.ed-table__row').first().waitFor(); await settle();
    const turn = async selector => {
      const response = page.waitForResponse(r => new URL(r.url()).pathname === prefix + 'context/tenants');
      const [, received] = await Promise.all([page.locator(selector).click(), response]);
      requireCase((await received.json()).code === 0, 'PAGINATION_REQUEST_REJECTED');
      await settle();
    };
    if (!(await row(groupName(name)).count())) {
      const previous = page.locator('.ed-pagination .btn-prev');
      while (await previous.isEnabled()) await turn('.ed-pagination .btn-prev');
      for (let count = 0; !(await row(groupName(name)).count()) && count < 200; count++) {
        const next = page.locator('.ed-pagination .btn-next');
        if (!(await next.isEnabled())) break;
        await turn('.ed-pagination .btn-next');
      }
    }
    requireCase(await row(groupName(name)).count() === 1, 'GROUP_NOT_FOUND_IN_PAGES');
    await submit(row(groupName(name)).getByRole('button', { name: '进入集团' }), 'context/switch');
    await page.locator('header').getByText(fixture[name].tenantId, { exact: false }).waitFor();
    const organizations = await page.getByRole('menuitem', { name: '组织与学校', exact: true }).count();
    await page.waitForURL(url => url.hash === (organizations ? '#/organizations' : '#/groups'));
    await settle();
  };
  const choose = async (label, option) => {
    const dialog = page.locator('.ed-dialog:visible');
    const field = dialog.locator('.ed-form-item').filter({ hasText: new RegExp('^' + escape(label)) }).first();
    const expanded = field.locator('[aria-expanded]').first();
    if (!(await expanded.count()) || await expanded.getAttribute('aria-expanded') !== 'true') await field.locator('.ed-select').click();
    await page.locator('.ed-select-dropdown:visible .ed-select-dropdown__item').filter({ hasText: option instanceof RegExp ? option : new RegExp('^' + escape(option) + '$') }).first().click();
    await dialog.locator('.ed-dialog__header').click();
  };
  const save = route => submit(page.locator('.ed-dialog:visible').getByRole('button', { name: '保存', exact: true }), route);
  const organization = async (name, kind = '学校', parent = null) => {
    await page.getByRole('button', { name: '新增组织', exact: true }).click();
    if (kind !== '学校') await choose('类型', kind);
    await page.locator('input[name=organizationName]').fill(name);
    if (kind === '学校') await page.locator('input[name=schoolCode]').fill(name);
    if (parent) await choose('上级组织', parent);
    const result = await save('organizations/save');
    await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
    await row(name).waitFor();
    return result;
  };
  const api = (route, body = {}) => page.evaluate(async ({ prefix, route, body }) => {
    const credential = sessionStorage.getItem('de.enterprise.management.credential.v1');
    const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + credential };
    const current = await (await fetch(prefix + 'context/current', { method: 'POST', headers, body: '{}' })).json();
    if (current.code !== 0) return { code: current.code };
    const expected = { ...headers, 'X-DE-Context-Tenant': current.data.tenantId, 'X-DE-Context-Version': current.data.version };
    const result = await (await fetch(prefix + route, { method: 'POST', headers: expected, body: JSON.stringify(body) })).json();
    return { code: result.code, data: result.code === 0 ? result.data : null };
  }, { prefix, route, body });
  try {
    await step('initialization', async () => {
      await page.goto(enterprise('/login'));
      await page.getByRole('button', { name: '登录', exact: true }).waitFor();
      requireCase(await page.locator('.ed-loading-mask:visible').count() === 0, 'LOADING_MASK_VISIBLE');
    });
    await step('wrong-password', () => login(input.credential.username, 'DefinitelyWrong-' + input.nonce, 20002), [20002]);
    await step('platform-login', async () => { await login(input.credential.username, input.credential.password); await page.waitForURL(url => url.hash === '#/groups'); });
    await step('platform-provision', async () => {
      for (const name of ['admina', 'adminb', 'member']) {
        const user = input.fixture[name];
        await page.locator('input[name=newUsername]').fill(user.username);
        await page.locator('input[name=displayName]').fill(groupName(name));
        await page.locator('input[name=temporaryPassword]').fill(user.initialPassword);
        const result = await submit(page.getByRole('button', { name: '创建平台用户', exact: true }), 'users/create');
        fixture[name] = { userId: result.id };
        await page.getByTestId('created-user').filter({ hasText: result.id }).waitFor();
        requireCase(await page.locator('input[name=temporaryPassword]').inputValue() === '', 'PASSWORD_NOT_CLEARED');
        if (name !== 'member') {
          await page.locator('input[name=groupCode]').fill(groupName(name));
          await page.locator('input[name=groupName]').fill(groupName(name));
          const group = await submit(page.getByRole('button', { name: '创建集团', exact: true }), 'tenants/create');
          fixture[name].tenantId = group.id;
        }
      }
    });
    await step('pagination-inflight', async () => {
      let arrived; let release;
      const received = new Promise(resolve => { arrived = resolve; });
      const barrier = new Promise(resolve => { release = resolve; });
      const pattern = '**/api/enterprise/v1/context/tenants';
      await page.route(pattern, async route => {
        const response = await route.fetch(); arrived(); await barrier;
        await route.fulfill({ response });
      }, { times: 1 });
      try {
        const clicked = page.getByRole('button', { name: '刷新', exact: true }).click();
        await received;
        await page.waitForFunction(() => {
          const controls = [...document.querySelectorAll('.ed-pagination button')];
          return controls.length > 0 && controls.every(control => control.disabled);
        });
        release(); await clicked;
        await page.waitForFunction(() => !document.querySelector('.ed-button.is-loading'));
      } finally { release(); await page.unroute(pattern); }
    });
    await step('platform-read-only', async () => {
      await enter('admina');
      requireCase(await page.getByRole('menuitem', { name: '组织与学校', exact: true }).count() === 0, 'PLATFORM_ACQUIRED_GROUP_MANAGEMENT');
      await logout();
    });
    await step('mandatory-password-reset', () => reset(input.fixture.admina));
    await step('group-admin-login', async () => { await enter('admina'); await page.waitForURL(url => url.hash === '#/organizations'); });
    await step('organization-create', async () => {
      fixture.schoolA1 = await organization(groupName('schoola1'));
      fixture.schoolA2 = await organization(groupName('schoola2'));
      await organization(groupName('departmentparent'), '部门');
      fixture.department = await organization(groupName('departmentchild'), '部门', groupName('departmentparent'));
    });
    await step('organization-update-clear', async () => {
      await row(groupName('departmentchild')).getByRole('button', { name: '编辑', exact: true }).click();
      const parent = page.locator('.ed-dialog:visible .ed-form-item').filter({ hasText: /^上级组织/ });
      await parent.locator('.ed-select').hover();
      await parent.locator('.ed-select__clear, .is-show-close').first().click();
      await save('organizations/save');
      await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
      const pageResult = await api('organizations/page');
      const child = pageResult.data.records.find(item => item.id === fixture.department.id);
      requireCase(child?.parentId === null, 'PARENT_NOT_CLEARED');
      fixture.department.version = child.version;
    });
    await step('member-create', async () => {
      await page.getByRole('menuitem', { name: '成员管理', exact: true }).click();
      await page.getByRole('button', { name: '添加成员', exact: true }).click();
      await page.locator('input[name=memberUserId]').fill(fixture.member.userId);
      await choose('组织与学校', groupName('schoola1')); await choose('组织与学校', groupName('schoola2'));
      const result = await save('members/save'); fixture.member.memberId = result.id;
      await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
      await row(fixture.member.userId).waitFor();
    });
    await step('member-update-clear', async () => {
      await row(fixture.member.userId).getByRole('button', { name: '编辑', exact: true }).click();
      const close = page.locator('.ed-dialog:visible .ed-select .ed-tag__close');
      while (await close.count()) await close.first().click();
      await save('members/save'); await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
      const result = await api('members/page');
      requireCase(result.data.records.find(item => item.id === fixture.member.memberId).organizationIds.length === 0, 'MEMBER_ORGS_NOT_CLEARED');
    });
    await step('role-create', async () => {
      await page.getByRole('menuitem', { name: '角色与任职', exact: true }).click();
      for (const name of ['principal', 'finance']) {
        await page.getByRole('button', { name: '新增角色', exact: true }).click();
        await page.locator('input[name=roleCode]').fill(name);
        await page.locator('input[name=roleName]').fill(groupName(name));
        fixture[name] = await save('roles/save');
        await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' }); await row(groupName(name)).waitFor();
      }
    });
    await step('assignment-school-pairs', async () => {
      for (const [role, school] of [['principal', 'schoola1'], ['finance', 'schoola2']]) {
        await page.getByRole('button', { name: '新增任职', exact: true }).click();
        await choose('成员ID', new RegExp('\\(' + fixture.member.memberId + '\\)$'));
        await choose('角色', groupName(role)); await choose('学校范围', groupName(school));
        await save('assignments/save'); await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
      }
      const result = await api('assignments/page');
      requireCase(result.data.records.length === 2, 'ASSIGNMENT_COUNT_WRONG');
      for (const [role, school] of [['principal', 'schoolA1'], ['finance', 'schoolA2']]) {
        const assignment = result.data.records.find(item => item.roleId === fixture[role].id);
        requireCase(assignment?.schoolIds.length === 1 && assignment.schoolIds[0] === fixture[school].id, 'ROLE_SCHOOL_PAIRS_CHANGED');
      }
    });
    await step('reload', async () => { await page.reload(); await row(fixture.member.memberId).waitFor(); });
    await step('cas-conflict', async () => {
      await page.getByRole('menuitem', { name: '组织与学校', exact: true }).click();
      await row(groupName('departmentchild')).getByRole('button', { name: '编辑', exact: true }).click();
      requireCase((await api('organizations/save', { mode: 'UPDATE', id: fixture.department.id, expectedVersion: fixture.department.version, kind: 'DEPARTMENT', name: groupName('concurrent'), parentId: null, status: 'ACTIVE' })).code === 0, 'CAS_SETUP_FAILED');
      await submit(page.locator('.ed-dialog:visible').getByRole('button', { name: '保存', exact: true }), 'organizations/save', 50002);
      await page.locator('.ed-dialog:visible').getByText('记录或集团上下文已变化，请刷新后重新操作。', { exact: true }).waitFor();
      await page.locator('.ed-dialog:visible').getByRole('button', { name: '取消', exact: true }).click();
      await submit(page.getByRole('button', { name: '刷新', exact: true }), 'organizations/page'); await row(groupName('concurrent')).waitFor();
    }, [50002]);
    await step('cross-group-both-directions', async () => {
      await logout(); await reset(input.fixture.adminb); await enter('adminb');
      fixture.schoolB = await organization(groupName('schoolb'));
      requireCase((await api('organizations/school', { id: fixture.schoolA1.id })).code === 70002, 'B_TO_A_NOT_DENIED');
      await page.getByRole('menuitem', { name: '成员管理', exact: true }).click();
      await page.getByRole('button', { name: '添加成员', exact: true }).click();
      await page.locator('input[name=memberUserId]').fill(fixture.admina.userId); await save('members/save');
      await page.locator('.ed-dialog:visible').waitFor({ state: 'hidden' });
      await logout(); await login(input.fixture.admina.username, input.fixture.admina.password); await enter('admina');
      requireCase((await api('organizations/school', { id: fixture.schoolB.id })).code === 70002, 'A_TO_B_NOT_DENIED');
    }, [70002]);
    await step('context-switch-late-response', async () => {
      let held; let release;
      const received = new Promise(resolve => { held = resolve; });
      const barrier = new Promise(resolve => { release = resolve; });
      const pattern = '**/api/enterprise/v1/organizations/page';
      await page.route(pattern, async route => {
        heldRequest = route.request();
        const response = await route.fetch(); held(); await barrier;
        try { await route.fulfill({ response }); } catch { /* Request was deliberately cancelled during context transition. */ }
      }, { times: 1 });
      const oldRefresh = page.getByRole('button', { name: '刷新', exact: true }).click();
      await received; allowAbort = true;
      await page.getByRole('menuitem', { name: '集团管理', exact: true }).click();
      await enter('adminb'); release(); await oldRefresh; await page.unroute(pattern);
      requireCase(await page.getByRole('menuitem', { name: '组织与学校', exact: true }).count() === 0, 'SWITCHED_GROUP_HAS_STALE_CAPABILITIES');
      requireCase(await page.getByText(groupName('schoola1'), { exact: true }).count() === 0, 'LATE_RESPONSE_DISCLOSED_OLD_GROUP');
      allowAbort = false; heldRequest = null;
    });
    await step('no-management', async () => {
      await logout(); await reset(input.fixture.member); await enter('admina');
      requireCase(await page.getByRole('menuitem', { name: '角色与任职', exact: true }).count() === 0, 'ORDINARY_MEMBER_HAS_ADMIN_MENU');
      requireCase((await api('roles/page')).code === 70001, 'ORDINARY_MEMBER_API_NOT_DENIED');
      await page.goto(enterprise('/roles')); await page.waitForURL(url => url.hash === '#/groups');
    }, [70001]);
    await step('logout', async () => { await logout(); requireCase(await page.evaluate(() => sessionStorage.getItem('de.enterprise.management.credential.v1')) === null, 'LOCAL_CREDENTIAL_NOT_REMOVED'); });
    await step('session-expiry', async () => {
      await login(input.fixture.admina.username, input.fixture.admina.password); await enter('admina');
      requireCase((await api('auth/logout')).code === 0, 'SESSION_EXPIRY_SETUP_FAILED');
      await submit(page.getByRole('button', { name: '刷新', exact: true }), 'organizations/page', 20001);
      await page.waitForURL(url => url.hash === '#/login');
    }, [20001]);
    await Promise.all(pending);
    requireCase(!report.apiErrors.length && !report.assetErrors.length && !report.pageErrors.length && !report.networkErrors.length && !report.securityErrors.length, 'BROWSER_ERRORS_PRESENT');
    requireCase(report.assetChecks.some(item => item.path === '/enterprise.html' && item.passed) && report.assetChecks.every(item => item.passed), 'MANAGEMENT_ASSETS_NOT_BOUND_TO_JAR');
    report.passed = true;
  } catch (error) { report.errorCode = error.safeCode || 'MANAGEMENT_BROWSER_FAILED'; }
  finally {
    await context.close(); await browser.close(); report.finishedUnix = Date.now() / 1000;
    const output = path.resolve(args['out-dir']); fs.mkdirSync(output, { recursive: true });
    fs.writeFileSync(path.join(output, 'management-browser-results.json'), JSON.stringify(report, null, 2));
  }
  console.log(JSON.stringify({ passed: report.passed, runId: report.runId, cases: report.cases.length, failed: report.cases.filter(item => item.status !== 'passed').map(item => ({ id: item.id, code: item.code })) }));
  if (!report.passed) process.exitCode = 1;
}
main().catch(error => { console.log(JSON.stringify({ passed: false, code: error.safeCode || 'MANAGEMENT_RUNNER_FAILED' })); process.exitCode = 1; });
