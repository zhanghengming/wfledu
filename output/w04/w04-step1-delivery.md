# W04第1步交付与文档验收

日期：2026-10-08。用户要求先一步步推进，本轮只完成[W04工作包](../../project-docs/planning/w04-authorization.md)第1步，不进入角色／任职编码。

## 交付内容

- [工作包与完成标准](../../project-docs/planning/w04-authorization.md)：源码／环境基线、7步清单、逐功能整包门禁、业务与后续范围。
- [源码及工程设计](../../project-docs/technical/w04-authorization-design.md)：W03复用、嵌套DTO、权限决策／预览、生成列、幂等、撤权及迁移方案。
- [开发者架构复核](../../project-docs/development/w04-design-review.md)：识别并落实设计风险和后续必须验证的拒绝路径；独立人工评审未完成。
- 文档总入口、追溯、API／字段和MySQL候选字典已回链；W03摘要中的W05业务库／W06模板副本／W08配置页面分工错误已纠正，历史执行证据保留。

## 本轮已核对与未执行

SSH只读核对远程任务HEAD `0d0edb789766d98d99974485242009bc6e821970`、已有25项修改及空暂存区、源码入口、JAR摘要、两个任务进程及监听。JAR SHA256 `e33aa8af2d7dc3bf8156380774f994d7a4d09c119d6a6a874ee257c1fcf52887`。

本轮没有产品源码修改、应用构建、数据库访问／迁移、启动／停止／重启、配置修改或Git提交／推送。W03的182项Java／140项HTTP为历史通过记录，本轮未重跑；W04功能测试全部待实施。

## 本次文档验收流程

1. 检查project-docs／PHASE0及本回执链接目标、代码围栏、Markdown表列数、示例JSON和主出处关系。
2. 执行 `node tools/phase0-check.mjs` 验证既有文档基线，不能用其32文件覆盖声称替代新W04文档检查。
3. 执行 `git -c core.safecrlf=false diff --check`；另检查未跟踪W04文件的尾随空白和文件结尾。
4. 独立重走上述文档清单；核对W04设计／待实现状态及依赖分工，不把设计校验提升为A/V/P业务通过。
5. 再次只读核对远程HEAD、diff摘要、产物／进程和监听，证明本轮未改远程任务源码、现有服务或运行产物。

首次文档检查实际通过：90份Markdown、928处本地文件链接、238张表和4个JSON示例；没有缺失目标、未闭合围栏、表列数或JSON错误。文件链接检查不证明既有文档所有标题锚点都有效；本轮新增引用的W04补充章节另作人工核对。Phase0脚本退出0，32必需文件／22基线Markdown通过。正常Git行尾配置下diff --check退出0，新W04文件尾随空白与结尾检查通过。

初次差异检查额外覆盖core.autocrlf=false，导致原有CRLF被误判为尾随空白；撤去该命令级覆盖后按仓库正常配置复查通过，没有转换任何文件行尾或掩盖空白错误。

再次SSH只读核对：HEAD、远程diff摘要、JAR SHA256、两个Java PID／开始时间及全部上述监听与设计前一致；未改远程检出或运行产物。设计第1步不操作现有数据库／服务，独立人工评审仍待用户／团队。

## 可复现的文档检查命令

在本地工作区根目录用PowerShell执行以下检查，脚本只读项目文档；随后执行node与Git命令。这是文档验收，不是产品功能测试。

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

## 交付后独立文档复验

最终文档生成后已独立再次执行上述流程：90份Markdown／928处本地文件链接／238张表／4个JSON示例检查退出0；Phase0基线检查退出0；正常Git配置的差异检查通过。新加入的字段与存储补充采用显式w04-api-design／w04-storage-design锚点，引用与目标已逐一核对。未复用首次结果，未进行产品功能测试。
