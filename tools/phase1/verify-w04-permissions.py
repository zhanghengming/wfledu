"""Fresh packaged authorization configuration acceptance; isolated synthetic runtime only."""
import configparser, hashlib, importlib.util, json, os, secrets, subprocess, sys, time, uuid
import urllib.request, urllib.error
from pathlib import Path
ROOT=Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
SOURCE=ROOT/'source'
HOME=ROOT/'runtime/w03-control-home'
PREFIX='http://127.0.0.1:18120/de2api/api/enterprise/v1/'
CASES=[]
IDENTITY=None
START=time.time()

def main():
    global IDENTITY
    spec=importlib.util.spec_from_file_location('permission_context',SOURCE/'tools/phase1/login-test-context.py')
    context=importlib.util.module_from_spec(spec);spec.loader.exec_module(context)
    IDENTITY=context.snapshot()
    # A fresh attempt cannot leave an older success usable if this run later fails.
    (ROOT/'logs/delivery-gate.json').write_text(json.dumps({'schemaVersion':1,'passed':False,'state':'PERMISSION_ACCEPTANCE_RUNNING',
                                                          'runId':run_id,'identity':IDENTITY,'finishedUnix':time.time()}))
    private=HOME/'accounts.json'
    assert private.stat().st_mode & 0o777==0o600
    accounts=json.loads(private.read_text());assert accounts['syntheticOnly'] is True
    import re
    database=accounts['database'];assert re.fullmatch(r'de_phase1_w03_control_[a-f0-9]{12}',database)
    client=ROOT/'runtime/conf/w03-client.cnf';assert client.stat().st_mode & 0o777==0o600
    cfg=configparser.ConfigParser(interpolation=None);cfg.read(client)
    assert cfg['client']['port']=='13306' and cfg['client']['host']=='127.0.0.1' and cfg['client']['user']=='de_phase1_w03_test'
    def sql(statement):
        result=subprocess.run(['/usr/bin/mysql','--defaults-file='+str(client),'--batch','--skip-column-names',database],input=statement,text=True,capture_output=True)
        if result.returncode:raise RuntimeError('SYNTHETIC_SQL_FAILED')
        return result.stdout.strip()
    assert sql('SELECT marker FROM w03_test_owner')=='synthetic-only-retain-no-drop'
    run=uuid.uuid4().hex
    def post(case,path,body,token=None,expected=0,raw=False):
        assert case not in {c['id'] for c in CASES}
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(PREFIX+path,data=(body if raw else json.dumps(body)).encode(),headers=headers)
        try:response=urllib.request.urlopen(request,timeout=20)
        except urllib.error.HTTPError as failure:response=failure
        with response:
            result=json.load(response);actual=result.get('code')
            assert response.headers.get('Cache-Control')=='no-store'
            CASES.append({'id':case,'status':'passed' if actual==expected else 'failed','expected':expected,'actual':actual,'httpStatus':response.status})
            if actual!=expected:raise RuntimeError('PERMISSION_CASE_FAILED_'+case)
            if expected:assert not result.get('data')
            return result.get('data')
    def proof(case,condition):
        assert case not in {c['id'] for c in CASES}
        CASES.append({'id':case,'status':'passed' if condition else 'failed'})
        if not condition:raise RuntimeError('PERMISSION_PROOF_FAILED_'+case)
    operator=accounts['operator']
    root=post('pc.operator-login','auth/login',{'username':operator['username'],'password':operator['password']})['credential']
    def user(label):
        name='pc_'+label+'_'+run[:12];initial=secrets.token_urlsafe(24);password=secrets.token_urlsafe(24)
        uid=post(label+'.user','users/create',{'username':name,'displayName':label,'temporaryPassword':initial},root)['id']
        token=post(label+'.initial','auth/login',{'username':name,'password':initial})['credential']
        post(label+'.reset','auth/password',{'previousPassword':initial,'newPassword':password},token)
        return uid,post(label+'.login','auth/login',{'username':name,'password':password})['credential']
    def group(label):
        uid,token=user(label)
        data=post(label+'.tenant','tenants/create',{'code':'PC_'+label+'_'+run[:12],'name':'Synthetic '+label,'administratorUserId':uid},root)
        post(label+'.select','context/switch',{'tenantId':data['id'],'expectedVersion':'1'},token)
        school=post(label+'.school','organizations/save',{'mode':'CREATE','kind':'SCHOOL','name':'Synthetic school','schoolCode':'PC_'+label+'_'+run,'status':'ACTIVE'},token)['id']
        return data,uid,token,school
    a,au,admin,school=group('pcA');b,bu,other,foreign=group('pcB')
    du,delegate=user('pcDelegate')
    post('pc.delegate-member','members/save',{'mode':'CREATE','userId':du,'status':'ACTIVE','organizationIds':[]},admin)
    post('pc.delegate-select','context/switch',{'tenantId':a['id'],'expectedVersion':'1'},delegate)
    def subject(kind,id):return {'type':kind,'id':id}
    su=subject('USER',du);sa=subject('USER',au);sb=subject('USER',bu);so=subject('ORG',school)
    def epoch(group=a):return sql('SELECT access_epoch FROM de_ent_tenant WHERE id='+group['id'])
    def batch(target,key,changes,group=a):return {'subject':target,'expectedEpoch':epoch(group),'idempotencyKey':key,'changes':changes}
    def change(ids=(school,),effect='ALLOW',action='VIEW'):
        return {'operation':'UPSERT','policyKind':'DATA_ACCESS','resourceType':'DATASET','resourceScope':{'kind':'ALL_DATASETS_IN_TENANT'},'schoolScope':{'kind':'EXPLICIT','ids':list(ids)},'action':action,'effect':effect}
    def state(group=a):
        tables=['de_ent_grant','de_ent_grant_school','de_ent_subject','de_ent_admin_grant','de_ent_idempotency','de_ent_audit_event']
        chunks=[sql('SELECT id,version,access_epoch,updated_at,updated_by FROM de_ent_tenant WHERE id='+group['id'])]
        for table in tables:
            columns = 'id,version,created_at,updated_at,created_by,updated_by,tenant_id,principal_kind,user_id,app_id,principal_key,operation,idempotency_key,HEX(request_digest),response_ref,CAST(result_metadata AS CHAR),state,expires_at' if table=='de_ent_idempotency' else '*'
            chunks.append(sql('SELECT '+columns+' FROM '+table+' WHERE tenant_id='+group['id']+' ORDER BY id'))
        return hashlib.sha256('\n'.join(chunks).encode()).hexdigest()
    before=state()
    post('pc.org-empty-read','permissions/rules/page',{'subject':so},admin)
    post('pc.org-cap-empty-read','admin-capabilities/page',{'subject':so},admin)
    proof('pc.read-does-not-write',state()==before)
    post('pc.org-write','permissions/batch',batch(so,'permission-org-'+run,[change()]),admin)
    role=post('pc.role-create','roles/save',{'mode':'CREATE','code':'rector','name':'Principal','status':'ACTIVE'},admin)['id']
    post('pc.role-write','permissions/batch',batch(subject('ROLE',role),'permission-role-'+run,[{**change(),'schoolScope':{'kind':'ASSIGNMENT'}}]),admin)
    first_body=batch(su,'permission-replay-'+run,[change()])
    first=post('pc.user-write','permissions/batch',first_body,admin)
    grant=first['results'][0]['grantId'];before=state()
    replay=post('pc.replay','permissions/batch',first_body,admin)
    proof('pc.replay-no-write',replay['replayed'] is True and replay['results']==first['results'] and state()==before)
    post('pc.replay-changed','permissions/batch',{**first_body,'changes':[change(effect='DENY')]},admin,50002)
    post('pc.duplicate-natural','permissions/batch',batch(su,'permission-duplicate-'+run,[change()]),admin,50003)
    post('pc.allow-deny-independent','permissions/batch',batch(su,'permission-deny-'+run,[change(effect='DENY')]),admin)
    replay=post('pc.replay-old-epoch','permissions/batch',first_body,admin)
    proof('pc.replay-epochs',replay['committedEpoch']==first['committedEpoch'] and replay['currentEpoch']==epoch() and replay['currentEpoch']!=first['committedEpoch'])
    before=state()
    for label,target,ids,token,group in [('forward',sb,[school],admin,a),('reverse',su,[foreign],other,b)]:
        post('pc.subject-cross-'+label,'permissions/batch',batch(target,'permission-subject-cross-'+run,[change(ids)],group),token,70002)
    post('pc.school-cross-forward','permissions/batch',batch(su,'permission-school-cross-'+run,[change([foreign])]),admin,70002)
    post('pc.school-cross-reverse','permissions/batch',batch(sb,'permission-school-cross-'+run,[change([school])],b),other,70002)
    post('pc.mixed-atomic-refusal','permissions/batch',batch(su,'permission-mixed-'+run,[change(action='EXPORT'),change([foreign],effect='DENY',action='EXPORT')]),admin,70002)
    proof('pc.refusals-do-not-write',state()==before)
    post('pc.unknown-school','permissions/batch',batch(su,'permission-unknown-'+run,[change(['1'])]),admin,70002)
    post('pc.duplicate-school','permissions/batch',batch(su,'permission-dupe-school-'+run,[change([school,school])]),admin,10001)
    post('pc.empty-school','permissions/batch',batch(su,'permission-empty-school-'+run,[change([])]),admin,10001)
    post('pc.assignment-on-user','permissions/batch',batch(su,'permission-assignment-'+run,[{**change(),'schoolScope':{'kind':'ASSIGNMENT'}}]),admin,10001)
    for label,body in [('unknown',{**first_body,'tenantId':b['id']}),('numeric',{**first_body,'subject':{'type':'USER','id':int(du)}}),('null',{**first_body,'changes':[None]})]:
        post('pc.json-'+label,'permissions/batch',body,admin,10001)
    post('pc.json-duplicate','permissions/batch','{"subject":{"type":"USER","type":"ORG","id":"'+du+'"}}',admin,10001,True)
    post('pc.json-trailing','permissions/batch',json.dumps(first_body)+' {}',admin,10001,True)
    post('pc.anonymous','permissions/rules/page',{'subject':su},None,20001)
    post('pc.ordinary-no-configuration','permissions/rules/page',{'subject':su},delegate,70001)
    post('pc.operator-no-group','permissions/rules/page',{'subject':su},root,70001)
    page=post('pc.rules-read','permissions/rules/page',{'subject':su},admin)
    proof('pc.rules-read-state',page['rules']['total']==2 and {row['effect'] for row in page['rules']['records']}=={'ALLOW','DENY'})
    post('pc.rules-page-without-epoch','permissions/rules/page',{'subject':su,'pageNum':2},admin,10001)
    post('pc.rules-stale-epoch','permissions/rules/page',{'subject':su,'expectedEpoch':'1'},admin,50002)
    post('pc.school-page','permissions/rules/page',{'subject':su,'grantId':grant,'expectedVersion':'1','expectedEpoch':epoch()},admin)
    post('pc.school-page-stale-version','permissions/rules/page',{'subject':su,'grantId':grant,'expectedVersion':'2','expectedEpoch':epoch()},admin,50002)
    post('pc.school-page-cross','permissions/rules/page',{'subject':sb,'grantId':grant,'expectedVersion':'1','expectedEpoch':epoch(b)},other,70002)
    update={**change(),'grantId':grant,'expectedVersion':'1','status':'DISABLED'}
    post('pc.rule-update','permissions/batch',batch(su,'permission-update-'+run,[update]),admin)
    post('pc.rule-cas','permissions/batch',batch(su,'permission-cas-'+run,[update]),admin,50002)
    post('pc.rule-immutable','permissions/batch',batch(su,'permission-immutable-'+run,[{**update,'expectedVersion':'2','schoolScope':{'kind':'ALL_ACTIVE_IN_TENANT'}}]),admin,10001)
    replaced=post('pc.rule-delete-create','permissions/batch',batch(su,'permission-replace-'+run,[{'operation':'DELETE','grantId':grant,'expectedVersion':'2'},change()]),admin)
    proof('pc.rule-replaced-state',sql('SELECT COUNT(*) FROM de_ent_grant WHERE id='+grant)=='0' and sql('SELECT COUNT(*) FROM de_ent_grant_school WHERE grant_id='+grant)=='0' and replaced['results'][1]['grantId']!=grant)
    resource=post('pc.resource-create','resources/create',{'name':'Synthetic catalog'},admin)['id']
    rb=post('pc.resource-create-other','resources/create',{'name':'Other catalog'},other)['id']
    cat=post('pc.catalog-dashboard','permissions/catalog',{'subject':su,'resourceType':'DASHBOARD'},admin)
    proof('pc.catalog-scoped-metadata',{r['id'] for r in cat['resources']['records']}=={resource} and not any(x in json.dumps(cat) for x in ['componentData','canvasStyleData','union_sql']))
    dashboard={'operation':'UPSERT','policyKind':'RESOURCE_ACTION','resourceType':'DASHBOARD','resourceScope':{'kind':'EXACT','id':resource},'schoolScope':{'kind':'NONE'},'action':'VIEW','effect':'ALLOW'}
    post('pc.resource-cross-forward','permissions/batch',batch(su,'permission-resource-cross-'+run,[{**dashboard,'resourceScope':{'kind':'EXACT','id':rb}}]),admin,70002)
    post('pc.resource-cross-reverse','permissions/batch',batch(sb,'permission-resource-cross-'+run,[dashboard],b),other,70002)
    post('pc.resource-action-write','permissions/batch',batch(su,'permission-resource-'+run,[dashboard]),admin)
    post('pc.config-is-not-data-decision','resources/read',{'id':resource,'action':'VIEW'},delegate,70001)
    # Register native datasets only as controlled synthetic fixtures; public registration belongs to W05.
    ds=post('pc.dataset-id','resources/create',{'name':'Dataset fixture identifier'},admin)['id']
    sql('INSERT INTO core_dataset_group(id,name,node_type) VALUES('+ds+",'Dataset fixture','dataset')")
    sql("UPDATE de_ent_resource SET resource_type='DATASET' WHERE id="+ds+' AND tenant_id='+a['id'])
    exact={**change(action='EXPORT'),'resourceScope':{'kind':'EXACT','id':ds}}
    post('pc.dataset-exact','permissions/batch',batch(su,'permission-dataset-'+run,[exact]),admin)
    post('pc.dataset-drill','permissions/batch',batch(su,'permission-drill-'+run,[{**exact,'action':'DRILL'}]),admin,0)
    sql("UPDATE core_dataset_group SET node_type='folder' WHERE id="+ds)
    post('pc.dataset-folder-reject','permissions/batch',batch(su,'permission-folder-reject-'+run,[{**exact,'action':'VIEW'}]),admin,70002)
    sql("UPDATE core_dataset_group SET node_type='dataset' WHERE id="+ds)
    cap={'operation':'UPSERT','capability':'MANAGE_AUTHORIZATION','effect':'ALLOW','status':'ACTIVE'}
    own=post('pc.capabilities-read','admin-capabilities/page',{'subject':sa},admin)
    old=next(g for g in own['grants']['records'] if g['capability']=='MANAGE_AUTHORIZATION')
    before=state()
    post('pc.capabilities-last-admin','admin-capabilities/batch',batch(sa,'permission-last-admin-'+run,[{'operation':'DELETE','grantId':old['id'],'expectedVersion':old['version']}]),admin,70001)
    proof('pc.capabilities-last-admin-rollback',state()==before)
    post('pc.capabilities-platform-reject','admin-capabilities/batch',batch(su,'permission-platform-'+run,[{**cap,'capability':'PLATFORM_OPERATE'}]),admin,10001)
    # Same idempotency key in another operation is a separate command.
    cbody=batch(su,first_body['idempotencyKey'],[cap])
    post('pc.capabilities-write','admin-capabilities/batch',cbody,admin)
    before=state();creplay=post('pc.capabilities-replay','admin-capabilities/batch',cbody,admin)
    proof('pc.capabilities-replay-no-write',creplay['replayed'] is True and state()==before)
    post('pc.delegate-configuration','permissions/rules/page',{'subject':su},delegate)
    cap_page=post('pc.capabilities-delegate-read','admin-capabilities/page',{'subject':su},admin)
    delegated=cap_page['grants']['records'][0]
    sql("UPDATE de_ent_idempotency SET created_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,updated_at=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE operation='PERMISSIONS_BATCH' AND idempotency_key='"+first_body['idempotencyKey']+"' AND tenant_id="+a['id'])
    post('pc.idempotency-expired','permissions/batch',first_body,admin,50002)
    own_body=batch(su,first_body['idempotencyKey'],[change(action='EXPORT')])
    post('pc.delegate-own-write','permissions/batch',own_body,delegate)
    proof('pc.idempotency-user-isolation',sql("SELECT COUNT(*) FROM de_ent_idempotency WHERE tenant_id="+a['id']+" AND operation='PERMISSIONS_BATCH' AND idempotency_key='"+first_body['idempotencyKey']+"'")=='2')
    post('pc.same-user-other-member','members/save',{'mode':'CREATE','userId':au,'status':'ACTIVE','organizationIds':[]},other)
    post('pc.same-user-other-authority','admin-capabilities/batch',batch(subject('USER',au),'permission-other-authority-'+run,[cap],b),other)
    post('pc.same-user-other-switch','context/switch',{'tenantId':b['id'],'expectedVersion':'2'},admin)
    post('pc.same-user-other-key','permissions/batch',batch(subject('USER',au),first_body['idempotencyKey'],[change([foreign])],b),admin)
    post('pc.same-user-return-switch','context/switch',{'tenantId':a['id'],'expectedVersion':'3'},admin)
    proof('pc.idempotency-group-isolation',sql("SELECT COUNT(*) FROM de_ent_idempotency WHERE user_id="+au+" AND operation='PERMISSIONS_BATCH' AND idempotency_key='"+first_body['idempotencyKey']+"'")=='2')
    # Remove old administrator while a second active administrator exists.
    post('pc.capabilities-remove-old','admin-capabilities/batch',batch(sa,'permission-remove-old-'+run,[{'operation':'DELETE','grantId':old['id'],'expectedVersion':old['version']}]),delegate)
    post('pc.capabilities-old-admin-denied','admin-capabilities/page',{'subject':sa},admin,70001)
    # Revoke own qualification is refused if it would leave no authorization administrator.
    before=state()
    post('pc.capabilities-own-last-denied','admin-capabilities/batch',batch(su,'permission-remove-last-'+run,[{'operation':'DELETE','grantId':delegated['id'],'expectedVersion':delegated['version']}]),delegate,70001)
    proof('pc.capabilities-own-last-rollback',state()==before)
    # Out-of-band revocation is a guarded synthetic fixture, never a product API.
    sql("UPDATE de_ent_admin_grant SET status='DISABLED' WHERE id="+delegated['id']+' AND tenant_id='+a['id'])
    try:post('pc.revoked-replay-denied','permissions/batch',own_body,delegate,70001)
    finally:sql("UPDATE de_ent_admin_grant SET status='ACTIVE' WHERE id="+delegated['id']+' AND tenant_id='+a['id'])
    department=post('pc.linked-department-create','organizations/save',{'mode':'CREATE','kind':'DEPARTMENT','name':'Linked department','schoolId':foreign,'status':'ACTIVE'},other)['id']
    linked=subject('ORG',department)
    existing=post('pc.linked-department-grant','permissions/batch',batch(linked,'permission-linked-'+run,[change([foreign])],b),other)['results'][0]
    post('pc.linked-school-disable','organizations/save',{'mode':'UPDATE','id':foreign,'expectedVersion':'1','kind':'SCHOOL','name':'Synthetic school','schoolCode':'PC_pcB_'+run,'status':'DISABLED'},other)
    before=state(b)
    post('pc.unavailable-department-reject','permissions/batch',batch(linked,'permission-linked-invalid-'+run,[{**change(action='DRILL'),'schoolScope':{'kind':'ALL_ACTIVE_IN_TENANT'}}],b),other,70002)
    proof('pc.unavailable-department-no-write',state(b)==before)
    post('pc.disabled-subject-delete','permissions/batch',batch(linked,'permission-linked-cleanup-'+run,[{'operation':'DELETE','grantId':existing['grantId'],'expectedVersion':'1'}],b),other)
    proof('pc.disabled-subject-cleaned',sql('SELECT COUNT(*) FROM de_ent_grant WHERE id='+existing['grantId'])=='0' and sql('SELECT COUNT(*) FROM de_ent_grant_school WHERE grant_id='+existing['grantId'])=='0')
    proof('pc.runtime-unchanged',context.snapshot()==IDENTITY)

if __name__=='__main__':
    run_id=str(uuid.uuid4());passed=False;error=None
    try:main();passed=True
    except Exception as failure:error=str(failure) if isinstance(failure,RuntimeError) else type(failure).__name__
    report={'schemaVersion':1,'runId':run_id,'passed':passed,'startedUnix':START,'finishedUnix':time.time(),'identity':IDENTITY,'cases':CASES,'error':error}
    out=ROOT/'logs'/('w04-permissions-'+run_id);out.mkdir();(out/'results.json').write_text(json.dumps(report,indent=2))
    (ROOT/'logs/w04-step4-http.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'passed':passed,'runId':run_id,'cases':len(CASES),'error':error,'report':str(out/'results.json')}))
    sys.exit(0 if passed else 1)
