const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const sourceRoot = path.resolve(__dirname, '../..');
const frontend = path.join(sourceRoot, 'core/core-frontend');
const esbuild = require(frontend + '/node_modules/esbuild');
const CryptoJS = require(frontend + '/node_modules/crypto-js');
const source = fs.readFileSync(frontend + '/src/views/tools/HmacTool.ts', 'utf8');
const compiled = esbuild.transformSync(source, { loader: 'ts', format: 'cjs', target: 'es2022' }).code;
const prefix = 'synthetic-prefix';
const suffix = 'synthetic-suffix';
const secret = 'synthetic-HMAC-only-test-secret';
const encrypted = CryptoJS.AES.encrypt(JSON.stringify({ enable: true, secretKey: secret }),
  CryptoJS.enc.Utf8.parse(prefix + suffix), {
    iv: CryptoJS.enc.Utf8.parse('synthetic-testiv'),
    mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7
  });
const payload = CryptoJS.enc.Utf8.parse('synthetic-testiv').concat(encrypted.ciphertext).toString(CryptoJS.enc.Base64);
const envelope = prefix + payload + suffix;

function loader(modelResponse, status = 200) {
  const calls = [];
  const window = {};
  class FakeXHR {
    open(method, url) { this.url = url; calls.push(url); }
    send() {
      this.status = this.url.endsWith('/xpackModel') ? status : 200;
      this.responseText = JSON.stringify(this.url.endsWith('/xpackModel') ? modelResponse : { code: 0, data: envelope });
      this.readyState = 4;
      if (this.onreadystatechange) this.onreadystatechange();
    }
  }
  const module = { exports: {} };
  const context = {
    module, exports: module.exports, window, XMLHttpRequest: FakeXHR,
    location: { pathname: '/' }, console,
    require(name) { if (name === 'crypto-js') return CryptoJS; throw Error('Unexpected module: ' + name); }
  };
  vm.runInNewContext(compiled, context);
  return { apply: module.exports.securityConfig, window, calls };
}
const config = () => ({ method: 'post', baseURL: './de2api', headers: {} });

async function expectSigned(model, status = 200) {
  const subject = loader(model, status);
  const request = config();
  await subject.apply(request, '/de2api/chartData/data');
  assert.deepEqual(subject.calls, ['./de2api/xpackModel', './de2api/perSetting/hmac/info']);
  assert.equal(typeof request.headers['X-Date'], 'string');
  const signingString = 'dataease-key\nPOST /de2api/chartData/data\nX-Date: ' + request.headers['X-Date'] + '\n';
  const signature = crypto.createHmac('sha256', secret).update(signingString).digest('base64');
  assert.equal(request.headers.Authorization,
    'Signature keyId="dataease-key",algorithm="hmac-sha256",headers="@request-target X-Date",signature="' + signature + '"');
}
async function main() {
  const community = loader({ code: 0, data: null });
  const request = config();
  await community.apply(request, '/de2api/chartData/data');
  assert.deepEqual(community.calls, ['./de2api/xpackModel']);
  assert.equal(community.window.de_secret_key, 1);
  assert.equal(request.headers.Authorization, undefined);
  await community.apply(config(), '/de2api/chartData/data');
  assert.deepEqual(community.calls, ['./de2api/xpackModel']);
  await expectSigned({ code: 0, data: true });
  await expectSigned({ code: 0, data: false });
  await expectSigned({ code: 40001, data: null });
  await expectSigned({ code: 0, data: null }, 404);
  console.log('Login startup HMAC regressions: 5 passed (synthetic contracts; no real secrets).');
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
