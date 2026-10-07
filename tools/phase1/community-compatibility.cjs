const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../..');
const CryptoJS = require(root + '/source/core/core-frontend/node_modules/crypto-js');

async function main() {
  const credentials = JSON.parse(fs.readFileSync(root + '/runtime/conf/test-credentials.json', 'utf8'));
  const pid = fs.readFileSync(root + '/runtime/app.pid', 'utf8').trim();
  const cmdline = fs.readFileSync('/proc/' + pid + '/cmdline', 'utf8');
  if (!cmdline.includes(root + '/source/core/core-backend/target/CoreApplication.jar')) {
    throw Error('test application PID does not belong to this task');
  }
  const base = 'http://127.0.0.1:18100';
  const api = base + '/de2api';
  const home = await fetch(base + '/');
  if (home.status !== 200) throw Error('homepage failed: ' + home.status);
  const response = await fetch(api + '/dekey');
  const res = await response.json();
  if (response.status !== 200 || res.code !== 0 || typeof res.data !== 'string') {
    throw Error('public key endpoint did not succeed');
  }
  const separator = Buffer.from('-pk_separator-').toString('base64url') + '=';
  const index = res.data.lastIndexOf(separator);
  if (index < 1) throw Error('invalid public key envelope');
  const envelope = res.data.substring(0, index);
  const keyStr = res.data.substring(index + separator.length);
  const key = CryptoJS.enc.Utf8.parse(keyStr);
  const ivHash = CryptoJS.SHA256(keyStr);
  const iv = CryptoJS.lib.WordArray.create(ivHash.words.slice(0, 4));
  const pem = CryptoJS.AES.decrypt(envelope, key, {
    iv, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7
  }).toString(CryptoJS.enc.Utf8);
  const encrypt = text => crypto.publicEncrypt({
    key: Buffer.from(pem, 'base64'), format: 'der', type: 'spki', padding: crypto.constants.RSA_PKCS1_PADDING
  }, Buffer.from(text)).toString('base64');
  const login = async pwd => {
    const result = await fetch(api + '/login/localLogin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: encrypt('admin'), pwd: encrypt(pwd) })
    });
    const body = await result.json();
    if (result.status !== 200 || !body || typeof body.code !== 'number') {
      throw Error('unexpected legacy login response');
    }
    return body;
  };
  const good = await login(credentials.admin);
  if (good.code !== 0 || typeof good.data?.token !== 'string' || !good.data.token) {
    throw Error('valid community login failed, code=' + good.code);
  }
  const bad = await login('synthetic-invalid-password');
  if (bad.code !== 40001) throw Error('wrong-password response did not match existing DEException code');
  const results = [
    { case: 'homepage', status: home.status },
    { case: 'public key', path: '/de2api/dekey', code: res.code },
    { case: 'valid encrypted community login', code: good.code, tokenReturned: true },
    { case: 'wrong encrypted password', code: bad.code, rejected: true }
  ];
  fs.writeFileSync(root + '/logs/community-api-results.json', JSON.stringify(results, null, 2));
  console.log('Community compatibility checks: 4 passed; no password or token printed.');
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
