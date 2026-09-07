# MyBatis Mapper Checker — 任务拆分与进度

方案：`mybatis-mapper-checker-idea-plugin-implementation-plan.md`
状态标记：`[ ]` 未开始 `[~]` 进行中 `[x]` 完成

## 阶段 1 工程骨架
- [x] 1.1 Gradle Kotlin DSL 根工程、settings、wrapper（Gradle 9.6.0）
- [x] 1.2 checker-core 模块（纯 Java 17，无 IDEA 依赖，JUnit 5）
- [x] 1.3 checker-idea 模块（IntelliJ Platform Gradle Plugin 2.18.1，编译平台 IC 2024.2.6，since-build 233）
- [x] 1.4 plugin.xml、MapperCheckerBundle 资源文件（放在 core，两边共用）
- [x] 1.5 `gradlew build` 通过，`buildPlugin` 产出 zip

## 阶段 2 索引
- [x] 2.1 输入过滤：所有 XML，非 mapper / sqlMap 根标签产出空 map；覆盖 library roots
- [x] 2.2 MapperStatementIndex（statement / 片段 / parameterMap 同一索引，kind 区分；databaseId 变体用 fullId#dbId 键）
- [x] 2.3 MapperNamespaceIndex
- [x] 2.4 SqlFragmentIndex → 并入 2.2
- [x] 2.5 ParameterMapIndex → 并入 2.2
- [x] 2.6 索引测试（MapperIndexTest 8 例）

## 阶段 3 Mapper 解析
- [x] 3.1 core：ParameterNameNormalizer、OgnlIdentifierExtractor、SqlParameterExtractor、AliasGroupBuilder、GlobMatcher
- [x] 3.2 MyBatis XML（#{} ${} if/when/foreach/bind 局部名 CDATA selectKey include property 不递归）
- [x] 3.3 iBatis 2 XML（#x# $x$ property compareProperty iterate parameterMap procedure）
- [x] 3.4 AnnotationSqlParser（@Select 等，数组 + 常量求值，<script>，Provider 由调用方标 UNRESOLVED）
- [x] 3.5 StatementAssembler（两阶段 include，循环检测，动态 refid，缺失片段 → partiallyParsed）
- [x] 3.6 databaseId 合并、多文件同 namespace 合并、多文件同 fullId = 多候选
- [x] 3.7 解析测试（core 62 例 + MapperXmlParserTest 7 例）

## 阶段 4 Java 解析
- [x] 4.1 MapperInterfaceDetector（namespace 索引 / @Mapper / SQL 注解；default、static、继承方法跳过）
- [x] 4.2 MethodSignatureParameterResolver（@Param / 别名组 / 单 Bean 不检查 / 单 Map 转调用点 / 集合数组别名 / RowBounds 排除）
- [x] 4.3 MapperMethodInvocationExtractor
- [x] 4.4 StringCallInvocationExtractor（SqlSession / SqlSessionTemplate / SqlMapClient / SqlMapClientTemplate）
- [x] 4.5 StatementIdResolver（常量求值）；短 id 在 MapperRepository.findStatementsByShortId
- [x] 4.6 测试（MethodSignatureAndAnnotationTest 15 例、StringCallInvocationExtractorTest 4 例）

## 阶段 5 模块可见性
- [x] 5.1 ModuleVisibilityResolver（当前 → 直接依赖 → 传递依赖与库 → 兼容模式整项目；main 不见 test root）
- [x] 5.2 忽略路径模式（MapperRepository 过滤）
- [x] 5.3 Heavy Test（ModuleVisibilityHeavyTest 4 例：同模块 / 传递依赖 / sibling 不可见 / 兼容 fallback）

## 阶段 6 DAL-005 / DAL-006
- [x] 6.1 core：StatementLocationRules
- [x] 6.2 StatementLocator（接口方法 XML + 注解合并；两者皆有 → DAL-006；namespace 不存在 → 中置信度）
- [x] 6.3 多数据库变体备注、jar 内 statement 标记

## 阶段 7 DAL-001 声明级
- [x] 7.1 core：ParameterContractEngine（差集、别名组、大小写提示、Bean 备注、跨方法降级）
- [x] 7.2 声明级接入（ContractCheckEngine.checkMapperInterface）
- [x] 7.3 忽略参数（通配）/ 忽略 statement 过滤

## 阶段 8 DAL-001 数据流
- [x] 8.1 Map：put / 常量 key / Map.of / ofEntries / ImmutableMap.of / builder / 双花括号 / Maps.newHashMap / putAll / remove / clear / 逃逸 / 调用后 put 不计
- [x] 8.2 Bean：setter / Introspector 规则 / 链式 setter / 无 setter / builder 链 → UNRESOLVED
- [x] 8.3 跨方法：深度、循环、多实现、库代码、多 return 并集、返回后补充、调用路径、多消费者（CheckRunContext.finish）
- [x] 8.4 测试（JavaParameterResolverTest 26 例）

## 阶段 9 触发与报告
- [x] 9.1 三个 Action（编辑器右键 / Project 视图右键 / Tools 菜单）
- [x] 9.2 ContractCheckTask + CheckRunner + CheckRunContext + InvocationDiscovery（namespace 反查 + 注解搜索 + 引用搜索）
- [x] 9.3 Dumb Mode 提示不排队
- [x] 9.4 报告工具窗口（树：Module → 文件 → 问题；无法解析分组；统计条；规则 / 置信度过滤；双击跳 Java；右键跳 Mapper / 忽略此处 / 忽略参数 / 忽略 statement / 标记已确认）
- [x] 9.5 ReportExporter（Markdown / CSV，ReportExporterTest 3 例）
- [x] 9.6 MapperContractGlobalInspection（Inspect Code 批量模式）
- [x] 9.7 端到端测试（CheckRunnerEndToEndTest 12 例）

## 阶段 10 抑制与设置
- [x] 10.1 MapperCheckerSettings（Project 级，.idea/mybatis-mapper-checker.xml）
- [x] 10.2 MapperCheckerConfigurable 设置页
- [x] 10.3 SuppressionMatcher（组合 / @SuppressWarnings("DAL-001") / 行注释 mapper-checker: ignore）

## 阶段 11 导航
- [x] 11.1 MapperGotoDeclarationHandler（接口方法名、字符串 statementId）
- [x] 11.2 MapperLineMarkerProvider（默认关）
- [x] 11.3 MyBatisX / MyBatis plugin / MyBatisCodeHelperPro 已安装时接口方法导航让位

## 阶段 12 稳定性
- [x] 12.1 Heavy Test 全部通过
- [~] 12.2 取消 / 运行中编辑：ProgressIndicator 逐项 checkCanceled、SmartPsiElementPointer 已实现，待真机验证
- [x] 12.3 Plugin Verifier：IC 233.15619.7 / 241.19416.15 / 242.26775.15 / 243.26574.91 全部 Compatible（build-verify.log）
- [x] 12.4 真实项目试用（用户已完成一轮，反馈三条见下）

## 真机试用反馈修正（2026-09-05）
- [x] 分页参数误判：内置分页 / 排序参数名单 + 通配（PaginationDefaults），默认忽略，设置可关；带路径按最后一段匹配
- [x] 实体参数属性级检查：单 Bean 与 @Param Bean 展开属性（BeanPropertyCollector），与 SQL 完整路径比对（Index 版本 2 存路径，OGNL 提取路径），低置信度，锚点在实体字段；根整体未用只报根；设置可关
- [x] 导出报告详情：Markdown 增加按 statement 分组的详情（完整文案、参数 / 属性、Java 与 Mapper 完整路径和行号、备注、调用路径、候选）；CSV 增加说明、文件、行号列
- [ ] 第二轮真机试用

## 团队规范接入（2026-09-07，《开发规范：Query 与 Mapper 绑定》）
- [x] 规则编号切换为 DAL（MMC001/002/003 → DAL-001/005/006，`RuleId.fromCode` 兼容旧值），文案 / 设置 / 抑制注解同步
- [x] core：RuleOptions（Query 后缀、拷贝方法源位置表、模板 statement 列表、转换方法前缀）、NameSimilarity、UnresolvedReason.REFLECTIVE_COPY / PARTIAL_STATEMENT
- [x] DAL-004 / 020 / 030（JavaRules.checkJavaFile）、DAL-022（checkQueryParamNaming）、DAL-021（checkDuplicateQueryClasses）
- [x] DAL-010 / 011（QueryClassRules：Query 使用登记 + setter 引用搜索）
- [x] DAL-002（MapperSideRules.checkMissingProperties，仅实体参数）、DAL-003（IfBlockCollector + checkTemplateBinding）
- [x] partiallyParsed 与反射拷贝登记为覆盖缺口（无法解析分组）
- [x] 豁免文件 `.binding-scan-ignore.yml`（Exemption / ExemptionFileParser / ExemptionService）：报告"已豁免"分组、右键"豁免此处"、Markdown / CSV 导出、无效记录计数
- [x] 纯 Java 规则实时提示开关（JavaRulesLocalInspection，默认关）
- [x] 设置页：Query 后缀 / 拷贝方法 / 模板 id / 实时开关
- [ ] CI 无头扫描：不做（2026-09-07 决定）

## 扫描性能优化（2026-09-07 第二轮真机反馈：一直在扫描）
- [x] 字符串调用发现：几十次全项目引用搜索 → 词索引定位候选文件 + 单次 AST 扫描（方案 17.1）
- [x] DAL-010：逐属性全项目引用搜索 → 扫描时登记 setter 调用查表，只有疑似死字段才回退搜索
- [x] QueryClassIndex：只保留源码里的 Query 类，DAL-021 与候选文件共用一份，避免 jar 里 CriteriaQuery 之类的无效词搜索
- [x] CheckRunner：逐条读操作 → 50ms 时间片批量
- [x] BeanPropertyCollector.collect 走 CachedValuesManager 缓存
- [x] 纯 Java 规则与 DAL-010/011 全关时跳过整个 Java 文件遍历
- [ ] 第三轮真机验证扫描耗时

## include 的 `<property>` 替换（2026-09-07 真机反馈：片段里的条件全被报 DAL-001）
- [x] `IncludeRef`：refid 与 `<property>` 名值对一起进索引（编码进同一字符串，索引格式不变，VERSION 3）
- [x] `ParameterTemplate`：只把"参数名内部嵌 ${}"的原文存为模板（`#{${prefix}poiId}`、`<if test="${prefix}x">`），
      `${alias}.col = #{poiId}` 这种照旧，不受影响
- [x] `StatementAssembler`：展开片段时代入 property 再提取；外层 property 对内层片段可见，同名内层优先
- [x] 占位符没给值 → statement 标 partiallyParsed，不报 DAL-001，进"无法解析"分组
- [x] IncludePropertyEndToEndTest 9 例（自闭合 / 带 property / 片段套片段 / 跨文件全限定 refid / 拼参数名 / 拼列名 / 缺值 / 多层传递）

## 测试总数
- checker-core：84
- checker-idea：106（含 Heavy 4、端到端 39）

## 已知限制 / 待真机验证
- 报告窗口、设置页、右键菜单等 Swing UI 未做自动化测试，需 `gradlew :checker-idea:runIde` 人工核对。
- Inspect Code 入口只注册在批量模式；跨文件位置（另一方法里的 put）只在报告窗口展示。实时提示只覆盖四条纯 Java 规则，默认关。
- 豁免文件只支持"列表 + 平铺键值"形态的 YAML，锚点、多行字符串不支持。
- 为了扫描速度（方案 17.1）：只用 insert / update / delete 且不提 receiver 类型名、也没有任何 selectXxx / queryForXxx 的 DAO 文件不会被发现；关掉 DAL-011 时，set 发生在扫描范围外的属性不再报 DAL-010。
- 短 id（`selectList("query")`）依赖 getAllKeys 快照，key 很多的超大项目首次查询稍慢。
