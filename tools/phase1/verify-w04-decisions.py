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
    spec=importlib.util.spec_from_file_location('decision_context',SOURCE/'tools/phase1/login-test-context.py')
    context=importlib.util.module_from_spec(spec);spec.loader.exec_module(context)
    IDENTITY=context.snapshot()
    # A fresh attempt cannot leave an older success usable if this run later fails.
    (ROOT/'logs/delivery-gate.json').write_text(json.dumps({'schemaVersion':1,'passed':False,'state':'DECISION_ACCEPTANCE_RUNNING',
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
        request=urllib.request.Request(('http://127.0.0.1:18120'+path if path.startswith('/de2api/') else PREFIX+path),data=(body if raw else json.dumps(body)).encode(),headers=headers)
        try:response=urllib.request.urlopen(request,timeout=20)
        except urllib.error.HTTPError as failure:response=failure
        with response:
            result=json.load(response);actual=result.get('code')
            assert response.headers.get('Cache-Control')=='no-store'
            CASES.append({'id':case,'status':'passed' if actual==expected else 'failed','expected':expected,'actual':actual,'httpStatus':response.status})
            if actual!=expected:raise RuntimeError('DECISION_CASE_FAILED_'+case)
            if expected:assert not result.get('data')
            return result.get('data')
    def proof(case,condition):
        assert case not in {c['id'] for c in CASES}
        CASES.append({'id':case,'status':'passed' if condition else 'failed'})
        if not condition:raise RuntimeError('DECISION_PROOF_FAILED_'+case)
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
    a,au,admin,s1=group('pdA');b,bu,other,foreign=group('pdB')
    s2=post('pd.school2','organizations/save',{'mode':'CREATE','kind':'SCHOOL','name':'School 2','schoolCode':'PD_A2_'+run,'status':'ACTIVE'},admin)['id']
    uid,viewer=user('pdViewer')
    member=post('pd.member','members/save',{'mode':'CREATE','userId':uid,'status':'ACTIVE','organizationIds':[]},admin)
    post('pd.viewer-select','context/switch',{'tenantId':a['id'],'expectedVersion':'1'},viewer)
    finance=str(int(uuid.uuid4().hex[:15],16));teaching=str(int(uuid.uuid4().hex[:15],16));foreign_ds=str(int(uuid.uuid4().hex[:15],16))
    sql("INSERT INTO core_dataset_group(id,name,node_type) VALUES("+finance+",'Synthetic finance','dataset'),("+teaching+",'Synthetic teaching','dataset'),("+foreign_ds+",'Synthetic foreign','dataset')")
    sql("INSERT INTO de_ent_resource(id,tenant_id,resource_type,status) VALUES("+finance+","+a['id']+",'DATASET','ACTIVE'),("+teaching+","+a['id']+",'DATASET','ACTIVE'),("+foreign_ds+","+b['id']+",'DATASET','ACTIVE')")
    def epoch(g=a):return sql('SELECT access_epoch FROM de_ent_tenant WHERE id='+g['id'])
    def rule(resource=None,action='VIEW',effect='ALLOW',scope='ASSIGNMENT',schools=None,type='DATASET'):
        sc={'kind':scope}
        if schools is not None:sc['ids']=schools
        return {'operation':'UPSERT','policyKind':'DATA_ACCESS' if type=='DATASET' else 'RESOURCE_ACTION','resourceType':type,'resourceScope':{'kind':'EXACT','id':resource} if resource else {'kind':'ALL_DATASETS_IN_TENANT'},'schoolScope':sc,'action':action,'effect':effect}
    def grant(case,stype,sid,rules):return post(case,'permissions/batch',{'subject':{'type':stype,'id':sid},'expectedEpoch':epoch(),'idempotencyKey':case.replace('.','-')+'_'+run,'changes':rules},admin)
    def preview(case,resource,action='VIEW',target=uid,type='DATASET',token=admin,expected=0):return post(case,'permissions/preview',{'userId':target,'policyKind':'DATA_ACCESS' if type=='DATASET' else 'RESOURCE_ACTION','resourceType':type,'resourceId':resource,'action':action},token,expected)
    principal=post('pd.principal-role','roles/save',{'mode':'CREATE','code':'principal','name':'Principal','status':'ACTIVE'},admin)['id']
    financial=post('pd.finance-role','roles/save',{'mode':'CREATE','code':'finance','name':'Finance','status':'ACTIVE'},admin)['id']
    assign1=post('pd.principal-assignment','assignments/save',{'mode':'CREATE','memberId':member['id'],'roleId':principal,'status':'ACTIVE','schoolIds':[s1]},admin)
    assign2=post('pd.finance-assignment','assignments/save',{'mode':'CREATE','memberId':member['id'],'roleId':financial,'status':'ACTIVE','schoolIds':[s2]},admin)
    grant('pd.principal-grant','ROLE',principal,[rule(scope='ALL_ACTIVE_IN_TENANT'),rule(action='EXPORT')])
    grant('pd.finance-grant','ROLE',financial,[rule(finance)])
    p=preview('pd.finance-preview',finance)
    proof('pd.finance-pairs',set(p['allowedSchoolIds'])=={s1,s2} and {s['assignmentId'] for s in p['sources']}=={assign1['id'],assign2['id']} and p['executionReady'] is False)
    p=preview('pd.teaching-preview',teaching)
    proof('pd.teaching-no-cross-product',p['allowedSchoolIds']==[s1] and p['accessEpoch']==epoch() and p['resourceVersion']=='1')
    preview('pd.ordinary-preview-denied',finance,token=viewer,expected=70001)
    preview('pd.foreign-target',finance,target=bu,expected=70002)
    preview('pd.foreign-resource',foreign_ds,expected=70002)
    preview('pd.reverse-resource',finance,target=bu,token=other,expected=70002)
    dash=post('pd.dashboard-create','resources/create',{'name':'Synthetic empty dashboard'},admin)['id']
    post('pd.view-default-denied','resources/read',{'id':dash,'action':'VIEW'},viewer,70001)
    grant('pd.dashboard-view','USER',uid,[rule(dash,scope='NONE',type='DASHBOARD')])
    p=preview('pd.dashboard-preview',dash,type='DASHBOARD')
    result=post('pd.dashboard-read','resources/read',{'id':dash,'action':'VIEW'},viewer)
    proof('pd.preview-read-consistent',p['authorizationAllowed'] and p['allowedSchoolIds']==[] and result['componentData']=='[]' and result['tenantId']==a['id'])
    grant('pd.personal-export-deny','USER',uid,[rule(finance,action='EXPORT',effect='DENY',scope='EXPLICIT',schools=[s1])])
    p=preview('pd.export-preview',finance,action='EXPORT')
    proof('pd.deny-overrides-only-export',not p['authorizationAllowed'] and any(s['effect']=='DENY' and s['action']=='EXPORT' for s in p['sources']))
    p=preview('pd.view-after-export-deny',finance)
    proof('pd.view-still-allowed',set(p['allowedSchoolIds'])=={s1,s2})
    dept=post('pd.department-create','organizations/save',{'mode':'CREATE','kind':'DEPARTMENT','name':'Linked finance department','schoolId':s2,'status':'ACTIVE'},admin)['id']
    grant('pd.parent-org-grant','ORG',s2,[rule(teaching,scope='EXPLICIT',schools=[s2])])
    post('pd.org-member','members/save',{'mode':'UPDATE','id':member['id'],'expectedVersion':'1','userId':uid,'status':'ACTIVE','organizationIds':[dept]},admin)
    p=preview('pd.parent-org-preview',teaching)
    proof('pd.no-parent-inheritance',p['allowedSchoolIds']==[s1])
    grant('pd.department-grant','ORG',dept,[rule(teaching,scope='EXPLICIT',schools=[s2])])
    p=preview('pd.org-combined-preview',teaching)
    proof('pd.org-combined-sources',set(p['allowedSchoolIds'])=={s1,s2} and any(s['subjectType']=='ORG' and s['subjectId']==dept for s in p['sources']))
    post('pd.org-member-revoke','members/save',{'mode':'UPDATE','id':member['id'],'expectedVersion':'2','userId':uid,'status':'ACTIVE','organizationIds':[]},admin)
    p=preview('pd.org-revoked-preview',teaching)
    proof('pd.org-immediate',p['allowedSchoolIds']==[s1])
    grant('pd.personal-view-deny','USER',uid,[rule(finance,effect='DENY',scope='EXPLICIT',schools=[s2]),rule(finance,action='DRILL',scope='ALL_ACTIVE_IN_TENANT')])
    p=preview('pd.drill-preview',finance,action='DRILL')
    proof('pd.drill-view-intersection',p['allowedSchoolIds']==[s1])
    post('pd.assignment-revoke','assignments/save',{'mode':'UPDATE','id':assign1['id'],'expectedVersion':assign1['version'],'memberId':member['id'],'roleId':principal,'status':'DISABLED','schoolIds':[]},admin)
    p=preview('pd.teaching-after-revoke',teaching)
    proof('pd.assignment-immediate',not p['authorizationAllowed'] and p['allowedSchoolIds']==[])
    grant('pd.dashboard-deny','USER',uid,[rule(dash,effect='DENY',scope='NONE',type='DASHBOARD')])
    post('pd.old-session-revoked','resources/read',{'id':dash,'action':'VIEW'},viewer,70001)
    preview('pd.json-numeric',finance,target=int(uid),expected=10001)
    rootid=operator['userId'] if 'userId' in operator else sql("SELECT id FROM de_ent_user WHERE username='"+operator['username']+"'")
    p=preview('pd.platform-preview',finance,target=str(rootid))
    proof('pd.platform-view-only',set(p['allowedSchoolIds'])=={s1,s2} and p['reason']=='PLATFORM_GROUP_VIEW')
    p=preview('pd.platform-export-preview',finance,action='EXPORT',target=str(rootid))
    proof('pd.platform-export-denied',not p['authorizationAllowed'])
    post('pd.old-chart-denied','/de2api/chartData/getData',{},viewer,70001)
    post('pd.member-disable','members/save',{'mode':'UPDATE','id':member['id'],'expectedVersion':'3','userId':uid,'status':'DISABLED','organizationIds':[]},admin)
    post('pd.disabled-member-old-session','resources/read',{'id':dash,'action':'VIEW'},viewer,70001)
    p=preview('pd.disabled-member-preview',dash,type='DASHBOARD')
    proof('pd.disabled-member-no-policy',not p['authorizationAllowed'])
    for route in ['datasetData/previewData','visualization/save','visualization/findById','chartData/export','link/info','embedded/info','task/page']:
        post('pd.legacy-'+route,'/de2api/'+route,{},admin,70001)
    # Follow-up defects: revocation of an inactive identity and native template dependencies.
    sql("UPDATE de_ent_user SET status='DISABLED',identity_epoch=identity_epoch+1 WHERE id="+uid)
    revoked={'mode':'UPDATE','id':member['id'],'expectedVersion':'4','userId':uid,'status':'DISABLED','organizationIds':[]}
    post('repair.member-foreign','members/save',revoked,other,70002)
    post('repair.member-revoke','members/save',revoked,admin)
    proof('repair.member-persisted',sql('SELECT CONCAT(status,":",version) FROM de_ent_tenant_member WHERE id='+member['id'])=='DISABLED:5')
    post('repair.member-cas','members/save',revoked,admin,50002)
    post('repair.member-no-reactivate','members/save',{**revoked,'expectedVersion':'5','status':'ACTIVE'},admin,70002)
    post('repair.member-old-session','context/current',{},viewer,20001)
    parent=post('repair.template-create','resources/create',{'name':'Synthetic template dependency'},admin)['id']
    copy=post('repair.copy-create','resources/create',{'name':'Synthetic school copy'},admin)['id']
    sql("UPDATE de_ent_resource SET resource_kind='TEMPLATE' WHERE id="+parent)
    sql("UPDATE de_ent_resource SET resource_kind='SCHOOL_COPY',parent_resource_id="+parent+",school_id="+s1+" WHERE id="+copy)
    saved=grant('repair.copy-grant','USER',au,[rule(copy,type='SCHOOL_COPY',scope='EXPLICIT',schools=[s1])])
    p=preview('repair.copy-healthy',copy,type='SCHOOL_COPY',target=au)
    proof('repair.copy-healthy-policy',p['authorizationAllowed'] and p['allowedSchoolIds']==[s1] and p['executionReady'] is False)
    for label,fragment in [('deleted','delete_flag=1'),('foreign','org_id='+b['id']),('folder',"node_type='folder'")]:
        sql('UPDATE data_visualization_info SET '+fragment+' WHERE id='+parent)
        before=sql('SELECT CONCAT(version,":",access_epoch) FROM de_ent_tenant WHERE id='+a['id'])
        preview('repair.template-'+label,copy,type='SCHOOL_COPY',target=au,expected=70002)
        body={'subject':{'type':'USER','id':au},'expectedEpoch':epoch(),'idempotencyKey':'repair-template-'+label+'-'+run,'changes':[rule(copy,type='SCHOOL_COPY',scope='EXPLICIT',schools=[s1],action='EXPORT')]}
        post('repair.template-'+label+'-grant','permissions/batch',body,admin,70002)
        proof('repair.template-'+label+'-rollback',sql('SELECT CONCAT(version,":",access_epoch) FROM de_ent_tenant WHERE id='+a['id'])==before and sql("SELECT COUNT(*) FROM de_ent_idempotency WHERE tenant_id="+a['id']+" AND idempotency_key='"+body['idempotencyKey']+"'")=='0')
        sql("UPDATE data_visualization_info SET delete_flag=0,org_id="+a['id']+",node_type='panel' WHERE id="+parent)
    preview('repair.copy-foreign-reverse',copy,type='SCHOOL_COPY',target=bu,token=other,expected=70002)
    sql('DELETE FROM data_visualization_info WHERE id='+parent)
    preview('repair.template-missing',copy,type='SCHOOL_COPY',target=au,expected=70002)
    grant('repair.copy-cleanup','USER',au,[{'operation':'DELETE','grantId':saved['results'][0]['grantId'],'expectedVersion':'1'}])
    proof('pd.runtime-unchanged',context.snapshot()==IDENTITY)
if __name__=='__main__':
    run_id=str(uuid.uuid4());passed=False;error=None
    try:main();passed=True
    except Exception as failure:error=str(failure) if isinstance(failure,RuntimeError) else type(failure).__name__
    report={'schemaVersion':1,'runId':run_id,'passed':passed,'startedUnix':START,'finishedUnix':time.time(),'identity':IDENTITY,'cases':CASES,'error':error}
    out=ROOT/'logs'/('w04-decisions-'+run_id);out.mkdir();(out/'results.json').write_text(json.dumps(report,indent=2))
    (ROOT/'logs/w04-decisions-http.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'passed':passed,'runId':run_id,'cases':len(CASES),'error':error,'report':str(out/'results.json')}))
    sys.exit(0 if passed else 1)
