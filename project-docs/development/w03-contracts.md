# 11. W03实际DTO字段与总体候选的区分（2026-10-08）

本节仅对应已编码ManagementContract及管理服务；上文完整首期候选保留，未实施字段不能发送到当前接口。实测状态见[W03验收](../development/w03-acceptance.md)。所有请求对象拒绝未知字段、重复键、尾随JSON、隐式类型转换；最大请求64KiB、深度16、单字符串512、数字长度20。ID和expectedVersion均为1—9223372036854775807的正十进制JSON字符串，不接受数字、前导零或空值代替必填。文本长度按Unicode码点计，非空、无首尾空白；不改全局Jackson。

| DTO／路径 | 实际字段及约束 |
| --- | --- |
| Login／auth/login | username字符串；password为JSON字符串，慢哈希格式和长度由服务端检查，错误身份／密码统一登录失败；秘密不进入日志 |
| PasswordChange／auth/password | previousPassword、newPassword均JSON字符串；新密码12—128个Java字符，禁止复用旧密码；成功增加身份修订并撤销全部旧会话 |
| Empty／auth/logout、context/current | `{}`，不接受浏览器用户／集团／角色字段 |
| Switch／context/switch | tenantId、expectedVersion均必填正ID字符串；expectedVersion是会话修订，不能用集团版本替代 |
| Page／三个page接口 | pageNum可缺省或null→1，整数1—10000；pageSize可缺省或null→20，整数1—100；按id稳定排序，不接受keyword／sort／page |
| UserCreate／users/create | username匹配`[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}`，区分大小写且不可重用；displayName码点1—128；temporaryPassword JSON字符串12—128个Java字符；强制改密、不授平台资格 |
| TenantCreate／tenants/create | code码点1—64、name码点1—128；administratorUserId必填当前有效全局用户ID；创建初始成员及显式七项管理资格，不隐授业务VIEW |
| MemberSave／members/save | mode仅CREATE／UPDATE；userId必填且创建后不可变；status仅ACTIVE／DISABLED；organizationIds必填无重复正ID字符串数组，0—500项，表示完整替换，不是增量追加；每项必须属于当前集团且有效。CREATE不得提供id／expectedVersion，即使null；UPDATE二者必填 |
| OrganizationSave／organizations/save | mode、kind、name、status必填，kind为SCHOOL／DEPARTMENT；name码点1—128。SCHOOL必须schoolCode码点1—64且schoolId为空；DEPARTMENT的schoolCode为空，schoolId按真实学校关系填写。parentId显式null表示清空，更新为完整状态提交。kind、schoolCode、schoolId创建后不可变；CREATE不得提供id／expectedVersion，UPDATE必填 |
| Reference／organizations/school | id为当前集团学校组织ID；返回权威schoolCode／tenantId／修订，不接受业务表school_id直接冒充 |
| ResourceCreate／resources/create | 仅name码点1—128；id、tenantId、resourceType、任意组件等额外字段全部拒绝 |
| ResourceRead／resources/read | id必填；action仅VIEW／EDIT／EXPORT／DRILL；W03仅允许专属平台资格VIEW，其他操作拒绝；未知／外组资源统一不可见 |

资源和组织ID的学校关系来自服务器映射，不等于浏览器宣称的身份。响应ID／version／epoch显式字符串，page使用现有PageResult records/total/current/pages/size，不改社区Pager全局行为。外部错误采用现有码10001／20001／20002／40001／50002／50003／70001／70002；实际HTTP状态留在接口回执，客户端同时检查code。内部持久化错误不输出SQL／堆栈。正式鉴权、CAS、最后管理员、事务回滚和双集团校验属于服务规则，不能仅由字段验证替代。


## W03已编码的控制面契约（2026-10-08）

上文首期候选接口是总体设计；当前实际仅为SDK IdentityManagementApi、ManagementApi、ResourceOwnershipApi及[字段约束附录](w03-contracts.md)，验收状态见[W03手册](../development/w03-acceptance.md)。运行前缀`/de2api/api/enterprise/v1/`：GET ready；POST auth/login、auth/password、auth/logout、context/current、context/switch、users/create、tenants/create、tenants/page、members/save、members/page、organizations/save、organizations/page、organizations/school、resources/create、resources/read。

这些路径使用现有ResultMessage／PageResult和业务码；分页实际为pageNum/pageSize，不提供尚未实现的keyword/sort/page字段。随机Bearer管理会话不兼容社区认证；只有auth/login及不含信息的就绪接口无需已登录身份，首次强制改密期间仅改密／退出。管理模式关闭旧业务入口；关闭管理模式时社区接口行为保持，不把控制模式的限制写成对所有现有部署的兼容承诺。

资源创建当前只接收name，服务器生成空白原生看板与不可重绑定归属；资源读取只验证当前集团的VIEW且要求专属GROUP_READ_ALL。不能据此调用社区编辑器或取数接口。角色／资格公共编辑、完整业务权限、票据与嵌入契约仍是后续设计。
