"""Private browser input for the dedicated synthetic management runtime; stdout goes only to stdin."""
import importlib.util,json,os,secrets,uuid
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
assert HERE==ROOT/'source/tools/phase1'
spec=importlib.util.spec_from_file_location('gate',HERE/'login-test-context.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
private=ROOT/'runtime/w03-control-home/accounts.json'
assert private.stat().st_mode & 0o777==0o600 and private.stat().st_uid==os.getuid()
accounts=json.loads(private.read_text());assert accounts['syntheticOnly'] is True
run=uuid.uuid4().hex
fixture={name:{'username':'w08_'+name+'_'+run[:12],'initialPassword':secrets.token_urlsafe(24),'password':secrets.token_urlsafe(24)} for name in ['admina','adminb','member']}
print(json.dumps({'identity':gate.snapshot(),'credential':accounts['operator'],'fixture':fixture,'nonce':run[:12]}))
