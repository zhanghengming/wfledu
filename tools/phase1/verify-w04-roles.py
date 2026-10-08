"""W04 role/assignment packaged acceptance in the existing synthetic-only runtime."""
import configparser, hashlib, importlib.util, json, os, secrets, subprocess, sys, urllib.request, urllib.error, uuid
from pathlib import Path
ROOT=Path('/home/data_dev_zhm/dataease-phase1-test/w02-security')
SOURCE=ROOT/'source'
HOME=ROOT/'runtime/w03-control-home'
PREFIX='http://127.0.0.1:18120/de2api/api/enterprise/v1/'
CASES=[]
CONTROL=None
spec=importlib.util.spec_from_file_location('w04_context',SOURCE/'tools/phase1/login-test-context.py')
context=importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)
def main():
    global CONTROL
    CONTROL=context.control_snapshot()
    baseline='--baseline' in sys.argv
    private=HOME/'accounts.json'
    assert private.stat().st_mode & 0o777==0o600
    accounts=json.loads(private.read_text()); assert accounts['syntheticOnly'] is True
    database=accounts['database']
    import re
    assert re.fullmatch(r'de_phase1_w03_control_[a-f0-9]{12}',database)
    pid=int((HOME/'app.pid').read_text())
    args=[s.decode() for s in (Path('/proc')/str(pid)/'cmdline').read_bytes().split(b'\0') if s]
    assert args==json.loads((HOME/'arguments.json').read_text())
    assert '--server.port=18120' in args and (Path('/proc')/str(pid)).stat().st_uid==os.getuid()
    client=ROOT/'runtime/conf/w03-client.cnf'
    assert client.stat().st_mode & 0o777==0o600
    cfg=configparser.ConfigParser(interpolation=None);cfg.read(client)
    assert cfg['client']['port']=='13306' and cfg['client']['host']=='127.0.0.1' and cfg['client']['user']=='de_phase1_w03_test'
    def sql(statement):
        r=subprocess.run(['/usr/bin/mysql','--defaults-file='+str(client),'--batch','--skip-column-names',database],
                         input=statement,text=True,capture_output=True)
        if r.returncode: raise RuntimeError('Synthetic fixture SQL failed; no credentials or SQL printed')
        return r.stdout.strip()
    assert sql('SELECT marker FROM w03_test_owner')=='synthetic-only-retain-no-drop'
    run=uuid.uuid4().hex
    def post(case,path,body,token=None,expected=0):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(PREFIX+path,data=json.dumps(body).encode(),headers=headers)
        try:response=urllib.request.urlopen(request,timeout=15)
        except urllib.error.HTTPError as failure:response=failure
        with response:
            result=json.load(response); actual=result.get('code')
            assert response.headers.get('Cache-Control')=='no-store'
            if actual!=expected:
                CASES.append({'id':case,'status':'failed','expected':expected,'actual':actual,'httpStatus':response.status})
                raise AssertionError(case+' expected '+str(expected)+' got '+str(actual))
            if expected:assert not result.get('data')
            CASES.append({'id':case,'status':'passed','code':actual,'httpStatus':response.status})
            return result.get('data')
    operator=accounts['operator']
    root=post('w04.operator-login','auth/login',{'username':operator['username'],'password':operator['password']})['credential']
    def user(label):
        name='w04_'+label+'_'+run[:12];initial=secrets.token_urlsafe(24);password=secrets.token_urlsafe(24)
        id=post(label+'.create-user','users/create',{'username':name,'displayName':label,'temporaryPassword':initial},root)['id']
        token=post(label+'.initial-login','auth/login',{'username':name,'password':initial})['credential']
        post(label+'.reset','auth/password',{'previousPassword':initial,'newPassword':password},token)
        token=post(label+'.login','auth/login',{'username':name,'password':password})['credential']
        return id,token
    def group(label):
        id,token=user(label)
        group=post(label+'.create-group','tenants/create',{'code':'W04_'+label+'_'+run[:12],'name':'Synthetic '+label,'administratorUserId':id},root)
        post(label+'.select','context/switch',{'tenantId':group['id'],'expectedVersion':'1'},token)
        school=post(label+'.school','organizations/save',{'mode':'CREATE','kind':'SCHOOL','name':'School '+label,'schoolCode':'W04_'+label+'_'+run,'status':'ACTIVE'},token)['id']
        return group,token,school
    a,admin,school=group('A')
    b,other,foreign=group('B')
    uid,delegate=user('delegate')
    member=post('w04.delegate-member','members/save',{'mode':'CREATE','userId':uid,'status':'ACTIVE','organizationIds':[]},admin)['id']
    post('w04.delegate-select','context/switch',{'tenantId':a['id'],'expectedVersion':'1'},delegate)
    subject=sql("SELECT id FROM de_ent_subject WHERE tenant_id="+a['id']+" AND member_id="+member)
    next_id=int(sql("SELECT GREATEST(COALESCE(MAX(id),0)+1,1000) FROM de_ent_admin_grant"))
    sql("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES("+str(next_id)+","+a['id']+","+subject+",'MANAGE_MEMBERS','ALLOW','ACTIVE'),("+str(next_id+1)+","+a['id']+","+subject+",'MANAGE_ROLES','ALLOW','ACTIVE')")
    # Explicit synthetic organization grant; management configuration API belongs to later W04 units.
    orgsubject=int(sql("SELECT GREATEST(COALESCE(MAX(id),0)+1,1000) FROM de_ent_subject"))
    sql("INSERT INTO de_ent_subject(id,tenant_id,subject_type,org_id) VALUES("+str(orgsubject)+","+a['id']+",'ORG',"+school+")")
    sql("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES("+str(next_id+2)+","+a['id']+","+str(orgsubject)+",'MANAGE_AUTHORIZATION','ALLOW','ACTIVE')")
    def state():
        return sql("SELECT CONCAT(access_epoch,':',(SELECT COUNT(*) FROM de_ent_audit_event WHERE tenant_id="+a['id']+"),':',(SELECT COUNT(*) FROM de_ent_org_member WHERE member_id="+member+")) FROM de_ent_tenant WHERE id="+a['id'])
    before=state()
    post('w04.member-indirect-escalation','members/save',{'mode':'UPDATE','id':member,'expectedVersion':'1','userId':uid,'status':'ACTIVE','organizationIds':[school]},delegate,70001)
    assert state()==before
    if baseline:return
    def role(label,token=admin):
        return post(label+'.role-create','roles/save',{'mode':'CREATE','code':label,'name':label,'status':'ACTIVE'},token)['id']
    rector=role('rector');finance=role('finance');brole=role('brole',other)
    role_page=post('w04.role-read','roles/page',{},admin)
    assert {row['id'] for row in role_page['records']}=={rector,finance}
    other_page=post('w04.role-other-group-read','roles/page',{},other)
    assert {row['id'] for row in other_page['records']}=={brole}
    post('w04.role-cross-update','roles/save',{'mode':'UPDATE','id':brole,'expectedVersion':'1','code':'brole','name':'Bad','status':'DISABLED'},admin,70002)
    post('w04.role-reverse-cross-update','roles/save',{'mode':'UPDATE','id':rector,'expectedVersion':'1','code':'rector','name':'Bad','status':'DISABLED'},other,70002)
    post('w04.role-duplicate','roles/save',{'mode':'CREATE','code':'rector','name':'Duplicate','status':'ACTIVE'},admin,50003)
    second=post('w04.second-school','organizations/save',{'mode':'CREATE','kind':'SCHOOL','name':'Second','schoolCode':'W04_SECOND_'+run,'status':'ACTIVE'},admin)['id']
    def assignment(label,role_id,school_ids,status='ACTIVE',token=admin,expected=0):
        return post(label,'assignments/save',{'mode':'CREATE','memberId':member,'roleId':role_id,'schoolIds':school_ids,'status':status},token,expected)
    first=assignment('w04.assignment-create',rector,[school])
    financial=assignment('w04.financial-assignment',finance,[second])
    b_assignment=post('w04.other-assignment-create','assignments/save',{'mode':'CREATE','memberId':b['administratorMemberId'],'roleId':brole,'schoolIds':[foreign],'status':'ACTIVE'},other)
    post('w04.assignment-cross-update','assignments/save',{'mode':'UPDATE','id':b_assignment['id'],'expectedVersion':'1','memberId':b['administratorMemberId'],'roleId':brole,'schoolIds':[foreign],'status':'ACTIVE'},admin,70002)
    post('w04.assignment-reverse-cross-update','assignments/save',{'mode':'UPDATE','id':first['id'],'expectedVersion':'1','memberId':member,'roleId':rector,'schoolIds':[school],'status':'ACTIVE'},other,70002)
    post('w04.assignment-reverse-foreign-school','assignments/save',{'mode':'CREATE','memberId':b['administratorMemberId'],'roleId':brole,'schoolIds':[school],'status':'ACTIVE'},other,70002)
    post('w04.assignment-foreign-member','assignments/page',{'memberId':b['administratorMemberId']},admin,70002)
    post('w04.assignment-reverse-foreign-member','assignments/page',{'memberId':member},other,70002)
    post('w04.assignment-foreign-role-filter','assignments/page',{'roleId':brole},admin,70002)
    post('w04.assignment-reverse-foreign-role-filter','assignments/page',{'roleId':rector},other,70002)
    post('w04.assignment-reverse-foreign-school-filter','assignments/page',{'schoolId':school},other,70002)
    page=post('w04.assignment-pairs','assignments/page',{'memberId':member},admin)
    pairs={row['roleId']:row['schoolIds'] for row in page['records']}
    assert pairs=={rector:[school],finance:[second]}
    assignment('w04.assignment-duplicate',rector,[school],expected=50003)
    assignment('w04.foreign-school',finance,[foreign],expected=70002)
    assignment('w04.foreign-role',brole,[school],expected=70002)
    assignment('w04.active-empty',finance,[],expected=10001)
    assignment('w04.duplicate-school',finance,[school,school],expected=10001)
    post('w04.foreign-page-filter','assignments/page',{'schoolId':foreign},admin,70002)
    update={'mode':'UPDATE','id':first['id'],'expectedVersion':'1','memberId':member,'roleId':rector,'schoolIds':[school,second],'status':'ACTIVE'}
    post('w04.assignment-update','assignments/save',update,admin)
    post('w04.assignment-cas','assignments/save',update,admin,50002)
    post('w04.assignment-immutable-role','assignments/save',{**update,'expectedVersion':'2','roleId':finance},admin,10001)
    post('w04.assignment-disable-clear','assignments/save',{**update,'expectedVersion':'2','schoolIds':[],'status':'DISABLED'},admin)
    cleared=post('w04.assignment-read-disabled','assignments/page',{'memberId':member,'roleId':rector},admin)
    assert cleared['records'][0]['schoolIds']==[] and cleared['records'][0]['status']=='DISABLED'
    # Ordinary role manager can assign an ordinary role but cannot confer an administrative role.
    ordinary=role('ordinary',delegate)
    assignment('w04.delegate-ordinary-role',ordinary,[school],token=delegate)
    protected=role('protected')
    protected_subject=sql("SELECT id FROM de_ent_subject WHERE tenant_id="+a['id']+" AND role_id="+protected)
    sql("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES("+str(next_id+3)+","+a['id']+","+protected_subject+",'MANAGE_AUTHORIZATION','ALLOW','ACTIVE')")
    before=state()
    assignment('w04.assignment-indirect-escalation',protected,[school],token=delegate,expected=70001)
    assert state()==before
    post('w04.numeric-role-id','roles/save',{'mode':'UPDATE','id':int(rector),'expectedVersion':'1','code':'rector','name':'Bad','status':'ACTIVE'},admin,10001)
    post('w04.null-page','assignments/page',{'memberId':None},admin,10001)
    post('w04.unknown-role-field','roles/save',{'mode':'CREATE','code':'bad','name':'Bad','status':'ACTIVE','tenantId':b['id']},admin,10001)
    post('w04.role-create-null-id','roles/save',{'mode':'CREATE','id':None,'code':'bad','name':'Bad','status':'ACTIVE'},admin,10001)
    post('w04.old-business-route','../chartData/getData',{},delegate,70001)
    post('w04.assignment-unknown','assignments/save',{**update,'id':'999','expectedVersion':'1'},admin,70002)
    role_update={'mode':'UPDATE','id':finance,'expectedVersion':'1','code':'finance','name':'Renamed','status':'DISABLED'}
    post('w04.role-disable','roles/save',role_update,admin)
    post('w04.role-cas','roles/save',role_update,admin,50002)
    assignment('w04.disabled-role-active-assignment',finance,[school],expected=70001)
    post('w04.operator-no-group','roles/page',{},root,70001)
    post('w04.anonymous-role','roles/page',{},None,20001)
    post('w04.delegate-still-valid','roles/page',{},delegate)
    post('w04.self-disable-last-admin','members/save',{'mode':'UPDATE','id':a['administratorMemberId'],'expectedVersion':'1','userId':a.get('administratorUserId') or sql("SELECT user_id FROM de_ent_tenant_member WHERE id="+a['administratorMemberId']),'status':'DISABLED','organizationIds':[]},admin,70001)

if __name__=='__main__':
    baseline='--baseline' in sys.argv
    try:
        main()
        assert context.control_snapshot()==CONTROL
        passed=True
    except Exception as failure:
        passed=False
        error=type(failure).__name__+': '+str(failure)
    payload={'passed':passed,'cases':CASES,'controlRuntime':CONTROL,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=SOURCE,text=True).strip(),
             'jarSha256':hashlib.sha256((SOURCE/'core/core-backend/target/CoreApplication.jar').read_bytes()).hexdigest()}
    if not passed:payload['error']=error
    file=ROOT/'logs'/('w04-step2-old-bug.json' if baseline else 'w04-step2-http.json')
    file.write_text(json.dumps(payload,indent=2))
    print(json.dumps({'passed':passed,'count':len(CASES),'report':str(file),'error':payload.get('error')},ensure_ascii=False))
    sys.exit(0 if passed else 1)
