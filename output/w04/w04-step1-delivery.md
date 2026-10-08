# W04��1���������ĵ�����

���ڣ�2026-10-08���û�Ҫ����һ�����ƽ�������ֻ���[W04������](../../project-docs/planning/w04-authorization.md)��1�����������ɫ����ְ���롣

## ��������

- [����������ɱ�׼](../../project-docs/planning/w04-authorization.md)��Դ�룯�������ߡ�7���嵥�����������Ž���ҵ���������Χ��
- [Դ�뼰�������](../../project-docs/technical/w04-authorization-design.md)��W03���á�Ƕ��DTO��Ȩ�޾��ߣ�Ԥ���������С��ݵȡ���Ȩ��Ǩ�Ʒ�����
- [�����߼ܹ�����](../../project-docs/development/w04-design-review.md)��ʶ����ʵ��Ʒ��պͺ���������֤�ľܾ�·���������˹�����δ��ɡ�
- �ĵ�����ڡ�׷�ݡ�API���ֶκ�MySQL��ѡ�ֵ��ѻ�����W03ժҪ�е�W05ҵ��⣯W06ģ�帱����W08����ҳ��ֹ������Ѿ�������ʷִ��֤�ݱ�����

## �����Ѻ˶���δִ��

SSHֻ���˶�Զ������HEAD `0d0edb789766d98d99974485242009bc6e821970`������25���޸ļ����ݴ�����Դ����ڡ�JARժҪ������������̼�������JAR SHA256 `e33aa8af2d7dc3bf8156380774f994d7a4d09c119d6a6a874ee257c1fcf52887`��

����û�в�ƷԴ���޸ġ�Ӧ�ù��������ݿ���ʣ�Ǩ�ơ�������ֹͣ�������������޸Ļ�Git�ύ�����͡�W03��182��Java��140��HTTPΪ��ʷͨ����¼������δ���ܣ�W04���ܲ���ȫ����ʵʩ��

## �����ĵ���������

1. ���project-docs��PHASE0������ִ����Ŀ�ꡢ����Χ����Markdown��������ʾ��JSON����������ϵ��
2. ִ�� `node tools/phase0-check.mjs` ��֤�����ĵ����ߣ���������32�ļ��������������W04�ĵ���顣
3. ִ�� `git -c core.safecrlf=false diff --check`�������δ����W04�ļ���β��հ׺��ļ���β��
4. �������������ĵ��嵥���˶�W04��ƣ���ʵ��״̬�������ֹ����������У������ΪA/V/Pҵ��ͨ����
5. �ٴ�ֻ���˶�Զ��HEAD��diffժҪ��������̺ͼ�����֤������δ��Զ������Դ�롢���з�������в��

�״��ĵ����ʵ��ͨ����90��Markdown��928�������ļ����ӡ�238�ű���4��JSONʾ����û��ȱʧĿ�ꡢδ�պ�Χ������������JSON�����ļ����Ӽ�鲻֤�������ĵ����б���ê�㶼��Ч�������������õ�W04�����½������˹��˶ԡ�Phase0�ű��˳�0��32�����ļ���22����Markdownͨ��������Git��β������diff --check�˳�0����W04�ļ�β��հ����β���ͨ����

���β�������⸲��core.autocrlf=false������ԭ��CRLF������Ϊβ��հף���ȥ��������Ǻ󰴲ֿ��������ø���ͨ����û��ת���κ��ļ���β���ڸǿհ״���

�ٴ�SSHֻ���˶ԣ�HEAD��Զ��diffժҪ��JAR SHA256������Java PID����ʼʱ�估ȫ���������������ǰһ�£�δ��Զ�̼�������в����Ƶ�1���������������ݿ⣯���񣬶����˹������Դ��û����Ŷӡ�

## �ɸ��ֵ��ĵ��������

�ڱ��ع�������Ŀ¼��PowerShellִ�����¼�飬�ű�ֻ����Ŀ�ĵ������ִ��node��Git��������ĵ����գ����ǲ�Ʒ���ܲ��ԡ�

```powershell
@'
from pathlib import Path
from urllib.parse import unquote
import json,re,hashlib
files=sorted(Path('project-docs').rglob('*.md'))+[Path('PHASE0.md'),Path('output/w04/w04-step1-delivery.md')]
errors=[]; links=0;tables=0;examples=0
for p in files:
    content=p.read_text(encoding='utf-8-sig'); fence=None; body=[]; table_cols=None
    for n,line in enumerate(content.splitlines(),1):
        mark=re.match(r'^\s*(`{3,}|~{3,})(.*)$',line)
        if mark:
            if fence is None: fence=mark.group(1)[0]; body=[]; language=mark.group(2).strip()
            elif mark.group(1)[0]==fence:
                if language=='json':
                    examples+=1
                    try: json.loads('\n'.join(body))
                    except Exception as e: errors.append(f'{p}:{n}: JSON {e}')
                fence=None
            continue
        if fence:
            body.append(line); continue
        for m in re.finditer(r'\[[^\]\n]*\]\(([^)\n]+)\)',line):
            target=m.group(1).strip().split(' "')[0].strip('<>'); path=unquote(target.split('#')[0])
            if not path or re.match(r'^(https?://|mailto:|app:|plugin:|codex:)',path): continue
            links+=1
            candidate=Path(path) if re.match(r'^[A-Za-z]:[/\\]',path) else p.parent/path
            if not candidate.exists(): errors.append(f'{p}:{n}: missing link {target}')
        if line.strip().startswith('|'):
            columns=len(re.split(r'(?<!\\)\|',line.strip()))-2
            delimiter=bool(re.fullmatch(r'\s*\|(?:\s*:?-{3,}:?\s*\|)+\s*',line))
            if delimiter: table_cols=columns;tables+=1
            elif table_cols is not None and columns!=table_cols: errors.append(f'{p}:{n}: table columns {columns}!={table_cols}')
        else: table_cols=None
    if fence: errors.append(f'{p}: unclosed code fence')
    if p.name.startswith('w04-'):
        for n,line in enumerate(content.splitlines(),1):
            if line.rstrip()!=line: errors.append(f'{p}:{n}: trailing whitespace')
        if not content.endswith('\n'): errors.append(f'{p}: missing final newline')
print(json.dumps({'documents':len(files),'localLinks':links,'tables':tables,'jsonExamples':examples,'errors':errors},ensure_ascii=False))
raise SystemExit(bool(errors))
'@ | python -
node tools/phase0-check.mjs
git -c core.safecrlf=false diff --check
```

## ����������ĵ�����

�����ĵ����ɺ��Ѷ����ٴ�ִ���������̣�90��Markdown��928�������ļ����ӣ�238�ű���4��JSONʾ������˳�0��Phase0���߼���˳�0������Git���õĲ�����ͨ�����¼�����ֶ���洢���������ʽw04-api-design��w04-storage-designê�㣬������Ŀ������һ�˶ԡ�δ�����״ν����δ���в�Ʒ���ܲ��ԡ�
