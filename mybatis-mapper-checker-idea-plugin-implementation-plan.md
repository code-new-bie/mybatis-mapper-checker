# MyBatis Mapper Checker — IDEA 插件实现方案（V1）

> 版本：V1 定稿
> 日期：2026-09-05
> 前身：`archive/ibatis-mapper-checker-idea-plugin-implementation-plan.v0.md`（iBatis 为主的初版，已废弃）

---

## 1. 产品定位

### 1.1 一句话

**检查 MyBatis 项目里 Java 侧声明或传入的参数，与 Mapper SQL 实际使用的参数是否一致，手动触发，汇总成报告。兼容 iBatis 2。**

### 1.2 解决的问题

```java
public interface OrderMapper {
    List<Order> queryOrder(@Param("merchantId") Long merchantId,
                           @Param("poiId") Long poiId,
                           @Param("status") String status);
}
```

```xml
<mapper namespace="com.example.order.dao.OrderMapper">
    <select id="queryOrder" resultType="Order">
        SELECT * FROM orders
        WHERE merchant_id = #{merchantId}
          AND status = #{status}
    </select>
</mapper>
```

`poiId` 声明了、传了，SQL 里没有。代码能编译能运行，查出来的数据却比预期多。
靠人眼几乎发现不了，尤其当参数由别的方法拼出来、Mapper XML 又在另一个 Maven 模块里的时候。

插件要做的就是把这类"沉默的参数遗漏"找出来，指到具体那一行。

### 1.3 检查链路

```text
Mapper 接口方法签名 / DAO 调用点          Java 侧参数集合
              ↓                                  ↓
     statementId 定位  ──────────→  Mapper XML / 注解 SQL 参数集合
                                                 ↓
                                          两边做差集
                                                 ↓
                              参数未使用 / statement 不存在 / 多候选
                                                 ↓
                                        报告工具窗口，双击跳转
```

### 1.4 触发方式：手动，不实时

插件不注册实时 Inspection，编辑器里不出现任何波浪线、gutter 报错图标或状态栏提示。

```text
右键菜单 / Tools 菜单 → 检查 Mapper 参数契约
         ↓
后台任务，有进度，可取消
         ↓
报告工具窗口：按 Module → 文件分组，带置信度和备注
```

理由：这类检查的价值在"集中排查"，不在"边写边提示"。实时标线对老项目是持续干扰。

### 1.5 适用范围与优先级

```text
1. MyBatis Mapper 接口          orderMapper.queryOrder(merchantId, poiId)       ← 主路径，必须做好
2. MyBatis SqlSession 字符串     sqlSession.selectList("com.example...OrderMapper.queryOrder", params)
3. iBatis 2 / Spring iBatis      sqlMapClient.queryForList("Order.queryOrder", params)   ← 兼容路径
```

Mapper 侧：

```text
MyBatis   <mapper namespace="com.example.order.dao.OrderMapper">   XML statement
MyBatis   @Select / @Insert / @Update / @Delete                    注解 SQL
iBatis 2  <sqlMap namespace="Order">                              XML statement（兼容）
```

三种调用方式归一为同一个 `DaoInvocation` 模型，两种 XML 归一为同一个 `MapperStatement` 模型，规则层不区分来源。

### 1.6 明确不做

| 不做 | 原因 |
|---|---|
| 编辑器实时标线 | 减少干扰，见 1.4 |
| "Mapper 使用了 Java 未提供的参数"检查 | 动态 SQL 下可选条件合法，检查无意义，永久不做 |
| 关键参数 / 数据范围参数升级规则 | 所有参数一视同仁 |
| 自动修改 SQL | 列名、AND/OR、WHERE 结构都不确定 |
| `@SelectProvider` 等 Provider SQL | SQL 由 Java 拼出，无法静态确定，标无法解析 |
| MyBatis-Plus `BaseMapper` 内置方法、Wrapper | 继承来的方法跳过；Wrapper 留后续 |
| Lombok `@Builder` 链式构造 | 留后续 |
| 跨类继承、接口多实现的参数追踪 | 标无法解析 |
| Kotlin、Spring XML、数据库 Schema 校验、SQL 执行计划 | 不在范围 |
| GitLab CI、SonarQube、独立 CLI | 第一阶段只做 IDEA 插件 |
| CI 无头扫描模式（`runIde -PscanProject -PscanOut`） | 2026-09-07 定：流水线里要起完整 IDEA 平台，代价与收益不匹配，永久不做；插件只在 IDE 内交互使用 |

---

## 2. 命名

| 项 | 值 |
|---|---|
| 插件显示名 | **MyBatis Mapper Checker** |
| 插件 id | `com.mapperchecker.mybatis` |
| Gradle 根工程 | `mybatis-mapper-checker` |
| Gradle 子模块 | `checker-core`、`checker-idea` |
| Java 根包 | `com.mapperchecker` |
| 规则前缀 | `DAL`，沿用团队规范《开发规范：Query 与 Mapper 绑定》的编号；插件早期的 MMC001/002/003 对应 DAL-001/005/006 |
| 设置页路径 | Settings → Tools → MyBatis Mapper Checker |
| 工具窗口名 | MyBatis Mapper Checker |
| 设置持久化文件 | `.idea/mybatis-mapper-checker.xml` |
| 资源文件 | `messages/MapperCheckerBundle.properties`（默认中文） |

插件 id 和根包一旦发布不可更改，阶段 1 结束前确认。当前项目目录名 `ibatis-mapper-checker` 建议改为 `mybatis-mapper-checker`。

---

## 3. 技术基线

| 项目 | 方案 |
|---|---|
| Java 语言级别 | 17（IDEA 2023.3 运行于 JBR 17） |
| 构建 | Gradle Kotlin DSL + IntelliJ Platform Gradle Plugin 2.x |
| since-build | 233（IDEA 2023.3） |
| until-build | 不设 |
| Plugin Verifier 目标 | 2023.3 / 2024.1 / 2024.3 / 2025.x / 2026.x 各一个 |
| Java 分析 | Java PSI（不用 UAST，V1 只做 Java，PSI 精度更高、调试更直接） |
| XML 分析 | XML PSI |
| 项目级索引 | FileBasedIndex |
| 检查触发 | AnAction + Task.Backgroundable |
| 批量接入 | GlobalInspectionTool（只在 Inspect Code 中运行，不在编辑器实时运行） |
| 结果展示 | ToolWindow |
| 项目模型 | Project / Module / ProjectFileIndex / ModuleRootManager |

兼容原则：装了 MyBatisX、MyBatis Log 等主流插件的 IDEA 都能装本插件。避免只在新版本存在的 API，必要时用 `ApplicationInfo` 判断版本走分支。

---

## 4. 工程结构与分层

```text
mybatis-mapper-checker/
├── checker-core/          纯 Java，不依赖 IDEA
│   ├── model/             DaoInvocation、MapperStatement、ParameterReference、ContractIssue ...
│   ├── naming/            参数名归一化、别名组、OGNL 标识符提取
│   ├── contract/          差集计算、置信度判定
│   └── rule/              DAL-001 / DAL-005 / DAL-006
│
└── checker-idea/          所有 IDEA 相关代码
    ├── action/            三个触发 Action
    ├── run/               后台任务、运行级上下文
    ├── index/             FileBasedIndex 实现
    ├── mapper/            XML / 注解 SQL 解析、include 解析
    ├── java/              Mapper 接口识别、签名解析、数据流追踪、字符串调用识别
    ├── module/            模块可见性
    ├── report/            工具窗口、报告模型、导出
    ├── inspection/        GlobalInspectionTool 适配
    ├── navigation/        Ctrl+Click、gutter icon
    ├── suppress/          三种抑制方式
    └── settings/          Project 级设置、Configurable
```

### 4.1 checker-core 禁止依赖 IDEA API

`checker-core` 的 Gradle 依赖里没有 IntelliJ Platform，编译期就不可能出现 `PsiElement`、`VirtualFile`、`Project`、`Module`。

core 只认识纯业务模型。位置用 `SourceLocation`（路径 + 行 + 列），模块用 `String moduleName`。

```text
IDEA PSI / Index  →  翻译成纯模型  →  core 计算  →  ContractIssue  →  IDEA 报告窗口
    (idea 层)         (idea 层)        (core 层)       (core 层)          (idea 层)
```

收益：

- 参数名归一化、OGNL 提取、差集、置信度这些最容易出错的逻辑，用普通 JUnit 毫秒级测完
- IDEA 升级只影响 idea 层翻译代码，规则一行不动
- 以后加 CLI 或 Sonar 只换翻译层
- 规则里塞不进"再去 PSI 里找找"的逻辑，Index / Resolver / Rule 三层自然分开

边界举例：跨方法追踪要递归进入 `PsiMethod`，在 idea 层；core 只接收追踪完的 `ParameterReference` 集合。模块可见性依赖 `ModuleRootManager`，在 idea 层；core 拿到的是筛过的候选 statement 列表。

---

## 5. 核心模型

### 5.1 ParameterReference

```java
public final class ParameterReference {
    String rootName;                 // 比较用的根名
    Set<String> aliases;             // 无 @Param 时的别名组，如 {a, arg0, param1}；有明确名字时为空
    String propertyPath;             // 完整路径，如 query.poiId，仅用于展示
    ParameterSourceType sourceType;  // PARAM_ANNOTATION / METHOD_PARAM / MAP_PUT / MAP_OF / BEAN_SETTER
                                     // / XML_INLINE / XML_DYNAMIC_ATTR / XML_TEST_EXPR / ANNOTATION_SQL ...
    Confidence confidence;           // HIGH / MEDIUM / LOW
    SourceLocation location;
}
```

### 5.2 DaoInvocation

```java
public final class DaoInvocation {
    InvocationKind kind;             // MAPPER_METHOD / SQL_SESSION_CALL / SQLMAP_CLIENT_CALL
    Operation operation;             // SELECT / INSERT / UPDATE / DELETE / UNKNOWN
    String statementId;              // 已解析的完整 id；未解析时为 null
    List<ParameterReference> parameters;
    List<String> callPath;           // 跨方法追踪路径，如 [OrderDao.query, OrderDao.buildParams]
    SourceLocation location;
    String moduleName;
}
```

### 5.3 MapperStatement

```java
public final class MapperStatement {
    StatementSource source;          // MYBATIS_XML / MYBATIS_ANNOTATION / IBATIS_XML
    String namespace;
    String id;
    String fullId;
    StatementType type;              // select / insert / update / delete / statement / procedure
    List<ParameterReference> directParameters;
    List<String> includeRefs;
    String parameterMapRef;          // iBatis 2 parameterMap="..."，可为空
    boolean partiallyParsed;         // 注解 SQL 含无法求值片段 / include 动态 refid
    SourceLocation location;
    String moduleName;
}
```

### 5.4 ContractIssue

```java
public final class ContractIssue {
    RuleId ruleId;
    Severity severity;
    Confidence confidence;
    String message;                  // 中文主文案
    String remark;                   // 中文备注，可为空
    List<String> callPath;           // 可为空
    SourceLocation primaryLocation;  // 报告双击跳转位置
    SourceLocation secondaryLocation;// Mapper statement 位置
}
```

### 5.5 UnresolvedInvocation

不是问题，但要让使用者知道哪些调用没被覆盖：

```java
public final class UnresolvedInvocation {
    String statementId;              // 可为空
    UnresolvedReason reason;         // METHOD_PARAM / FIELD / MULTI_IMPL / DEPTH_EXCEEDED / PROVIDER
                                     // / DYNAMIC_INCLUDE / MAP_MUTATED / LIBRARY_CODE / STATEMENT_ID_DYNAMIC ...
    SourceLocation location;
}
```

### 5.6 CheckResult

```java
public final class CheckResult {
    List<ContractIssue> issues;
    List<UnresolvedInvocation> unresolved;
    Statistics statistics;           // 扫描文件数、DAO 调用数、成功解析数、无法解析数、已抑制数、各置信度问题数
}
```

---

## 6. 规则

| 规则 | 含义 | 默认级别 | 置信度 |
|---|---|---|---|
| DAL-001 | 参数已声明或传入，但 Mapper SQL 未使用 | Warning | 高 / 中 / 低，见下 |
| DAL-005 | statement 不存在 | Error | 高；可能定义在未索引依赖中时为中 |
| DAL-006 | statement 存在多个候选，无法确定目标 | Warning | 高 |

### 6.1 DAL-001 置信度

| 来源 | 置信度 | 备注文案 |
|---|---|---|
| 接口 `@Param` 声明 | 高 | 无 |
| 方法内 `Map.put` / `Map.of` | 高 | 无 |
| 接口无 `@Param` 的别名组 | 中 | 无 |
| 跨方法追踪 | 中 | 附调用路径 |
| Bean setter | 低 | 参数来自 Bean setter，该属性可能另有用途，请人工确认 |
| 实体属性（单 Bean / `@Param` Bean 展开） | 低 | 属性来自实体类声明，该实体可能被多个 statement 共用，请人工确认 |

实体属性级 DAL-001 的 Java 侧锚点（双击跳转、`primaryLocation`）落在**声明该实体的 DAO 方法参数上**，不落在实体类自己的字段 / getter / setter 上（2026-09-07 真机反馈：原来落在实体属性上，看不出是哪个方法、哪个 statement 的事；属性名已经在消息文案里，不靠位置区分）。DAL-010 / DAL-011 不受影响，仍然锚定在实体属性上——那两条本来就是跨 statement 聚合的，没有唯一对应的 DAO 方法可跳。

### 6.1A 内置分页参数忽略（真机试用后加入）

PageHelper、MyBatis-Plus、手写分页都会把 pageNum / pageSize / offset / limit / orderBy 等放进参数对象，SQL 里不引用（由拦截器拼 LIMIT）。
逐个报 DAL-001 全是误报，真机试用时占了绝大多数。内置名单与通配（`page*`、`*PageSize`、`*Offset`、`*Limit`、`*OrderBy` 等）
默认开启忽略，设置里可关闭或补充。带路径时只看最后一段（`q.pageSize` → `pageSize`）。

置信度只影响报告排序和展示，不影响是否报告。原则：**报告可疑，而不是断言错误**，由使用者判断。

### 6.1B 上游赋值分析（真机反馈 2026-09-07 加入）

真机场景：`query.dataStatuses` 声明了却没在 SQL 里用，报告只说"未使用"，看不出这值是上游真传了、还是压根没人给过——前者更像 bug（值被静默丢弃），后者更像历史遗留（死参数，删了就是）。`UpstreamAssignmentAnalyzer` 在 `CheckRunContext.finish()` 里对已经报出来的 DAL-001（声明的参数、实体属性）与 DAL-010 做一次调整，只影响置信度与备注，不影响是否报告。设置项 `upstreamAssignmentAnalysis` 默认开。

判定很朴素，只把字面量 `null` 当"没给值"，其余（变量、方法调用、new 出来的对象……）一律当"给了值"：

锚点现在都是 `PsiParameter`（见 6.1 的锚点说明：实体属性级也锚定在 DAO 方法的参数上），不能再靠"是不是 PsiParameter"区分两种来源，改按参数的**类型**分流：

```text
参数类型不是可展开的 Bean（标量 / 集合 / 数组，PARAM_ANNOTATION / METHOD_PARAM）
  → 找该 Mapper 方法的调用点（与单 Map 参数调用点追踪同一个 callSiteScope），看对应位置的实参
  → 找到一处非 null 实参就够了，早停：ASSIGNED，confidence.raise()
  → 全部调用点都传字面量 null：NOT_ASSIGNED，confidence.lower()
  → 一个调用点都没找到（反射调用 / 尚未接入）：UNKNOWN，不下结论，原样返回

参数类型是可展开的 Bean（实体属性 BEAN_PROPERTY，与 DAL-010）
  → owner 直接取参数的类型（不再从锚点向上找外层 PsiClass——锚点在 DAO 方法里，向上找到的是
    Mapper 接口，不是实体类），复用扫描阶段登记的 setterUsages 表
    （"实体全限定名#属性" → SetterEvidence：位置 + 是否见过非 null 值）
  → 命中且 anyRealValue：ASSIGNED；命中但全是 null：NOT_ASSIGNED；没命中：UNKNOWN
  → 已知限制：若 Mapper 方法参数声明为父类类型（如 BaseQuery），而调用点实际构造并操作的是子类
    （OrderQuery），两者 FQN 不一致，setterUsages 查不到——退化为 UNKNOWN，不影响正确性，只是少一条备注。
    Mapper 参数与调用点用同一个具体类型（绝大多数场景）不受影响
```

`Confidence.raise()` / `.lower()`：HIGH 不再升、LOW 不再降，中间各挪一级，静态分析不装作能看穿数据流，不越级到 HIGH。

### 6.2 DAL-005 对 Mapper 接口的含义

接口方法既没有 XML statement 也没有 SQL 注解，运行时 MyBatis 抛 `BindingException`，是确定性错误。定位到方法名。

### 6.3 DAL-006 触发情形

- 多个模块存在同 fullId 的 statement 且都可见
- 同一 XML 或多个 XML 中 fullId 重复（databaseId 不同的除外，见 8.7）
- 接口方法既有 XML statement 又有 SQL 注解
- 接口方法重载（MyBatis 本身不允许）
- MyBatis 短 id 调用 `selectList("query")` 命中多个 namespace

### 6.4 实现顺序

```text
DAL-005（接口方法 → XML / 注解定位）
   ↓
DAL-001 接口声明级 @Param
   ↓
DAL-001 方法内 Map
   ↓
DAL-001 Bean setter（低置信度）
   ↓
DAL-001 跨方法
   ↓
DAL-006
   ↓
SqlSession 字符串调用 + iBatis 2 兼容
   ↓
团队规范规则（6.5，2026-09-07 加入）
```

先把定位做准，再做参数差异。

### 6.5 团队规范规则（《开发规范：Query 与 Mapper 绑定》）

规范共 11 条，插件全部覆盖；编号沿用规范，插件原 MMC001/002/003 对应 DAL-001/005/006。除下表标"全局"者外都随范围检查；标"纯 Java"者不依赖 Mapper，可开实时提示（见 15.4）。

| 规则 | 含义 | 默认级别 | 说明 |
|---|---|---|---|
| DAL-002 | Mapper 引用了实体类里不存在的属性 | Error | 只对实体类型参数（单 Bean / `@Param` Bean）检查，Map 参数不查；嵌套路径逐段校验；备注相似属性名（`NameSimilarity`） |
| DAL-003 | 模板语句里 `<if test="a">` 块内绑定的却是 `#{b}` | Error | 只对模板 statement（默认 listByQuery / listPageByQuery / getByQuery / countByQuery / save / update / updateByQuery，设置可改）；foreach item / index、bind 局部名不算 |
| DAL-004 | copyProperties 参数顺序写反，空对象被当作源 | Error（纯 Java） | 按内置 + 设置的"拷贝方法源参数位置"表判断（Spring 0、hjly BeanUtils 1、commons-beanutils 1、hutool 0）；源是 `new X()` 或初始化后从未 set / 重赋值的局部变量才报 |
| DAL-010 | 调用方 set 了字段，但 Query 类关联的所有语句都不引用它 | Warning（全局） | 关联语句 = 该实体作为参数出现过的 statement 集合；setter 有引用 → DAL-010，无引用 → DAL-011；无 setter 的属性、分页参数跳过；被反射拷贝当过目标的实体加备注 |
| DAL-011 | 既无人 set 也无语句引用的死字段 | WeakWarning（全局） | 同上 |
| DAL-020 | transform / convert / to / build 方法内使用反射拷贝 | Warning（纯 Java） | 转换方法 = 返回 Query 类，或方法名前缀命中且返回项目里的 Bean |
| DAL-021 | 跨模块同名 Query 类 | Warning（全局） | 以 Query 类后缀（默认 `Query`）按简单名查 `PsiShortNamesCache` |
| DAL-022 | DAO 方法的 Query 参数未注解为 `@Param("query")` | WeakWarning（纯 Java） | 只看 Mapper 接口方法里类型名命中 Query 后缀的参数 |
| DAL-030 | 跨层转换 `setA(x.getB())` 改了字段名 | Warning（纯 Java） | `NameSimilarity.classify`：拼写高度相似 → typo 文案；单复数 / 集合变体 → plural 文案；完全不同名不报 |

规范里"CI 无头扫描"一节不做（1.6）；"豁免文件"与"实时提示"见 15.4。DAL-010/011/021 只在 Module / 整项目范围跑，单文件范围不跑（进度条会多出两步"Query 类"与"同名类"）。

---

## 7. Mapper 侧：索引

### 7.1 索引清单

```text
MapperStatementIndex
  key    fullId                        com.example.order.dao.OrderMapper.queryOrder  /  Order.queryOrder
  value  namespace, id, type, databaseId, directParameters, includeRefs, parameterMapRef, 文件, 偏移

MapperNamespaceIndex
  key    namespace                     com.example.order.dao.OrderMapper
  value  文件列表                       用于判定"某接口是否有对应 XML"

SqlFragmentIndex
  key    fullId                        com.example.common.dao.Common.dataScope
  value  directParameters, includeRefs

ParameterMapIndex（iBatis 2）
  key    fullId                        Order.orderParamMap
  value  property 列表
```

### 7.2 Index 原则

Index 阶段只依赖当前文件内容。XML 只产出 namespace、statement、type、include 引用、直接参数引用。
**不在 Index 阶段展开跨文件 include**，跨文件放到 Resolver。

### 7.3 输入过滤

```text
文件类型 = XML
根标签 ∈ { mapper, sqlMap }             读取前 2KB 判断，或 DOCTYPE 含 mybatis / ibatis
范围 = 项目内容 + library roots           依赖 jar 里的 Mapper XML 也索引
不区分 source root / resource root        放在 src/main/java 下的 XML 同样索引
```

注解 SQL 不进索引，检查接口方法时直接读 PSI 注解。

---

## 8. Mapper 侧：解析

### 8.1 MyBatis XML

根标签 `<mapper namespace="...">`，namespace 通常是接口全限定名。

statement 标签：`select`、`insert`、`update`、`delete`。
片段：`<sql id="...">`，引用：`<include refid="..."/>`。

### 8.2 MyBatis 注解 SQL

```java
@Select("SELECT * FROM orders WHERE merchant_id = #{merchantId} AND status = #{status}")
List<Order> queryOrder(@Param("merchantId") Long merchantId, @Param("status") String status);
```

```text
@Select / @Insert / @Update / @Delete
  → value 为字符串或字符串数组，用 PsiConstantEvaluationHelper 逐段求值后拼接
  → 求不出的片段跳过，statement 标 partiallyParsed，该 statement 不报 DAL-001
  → 含 <script> 时按 XML 规则解析动态标签
  → #{} / ${} 提取规则同 XML

@SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider
  → 标 UNRESOLVED，原因 PROVIDER
```

### 8.3 iBatis 2 XML（兼容）

根标签 `<sqlMap namespace="...">`，namespace 为短名。

statement 标签：`select`、`insert`、`update`、`delete`、`statement`、`procedure`。
片段：`<sql id="...">`，引用：`<include refid="..."/>`。
参数映射：`<parameterMap id="..."><parameter property="..."/></parameterMap>`，statement 以 `parameterMap="..."` 引用并用 `?` 占位。

### 8.4 include 解析

两阶段：

```text
Index 阶段     Order.queryOrder  includes: [Common.scope]
               Common.scope      parameters: [merchantId, poiId]

Resolver 阶段  Order.queryOrder  实际参数 = 自身直接参数 ∪ Common.scope 参数 ∪ ...
```

支持 A → B → C → D 链式展开。必须检测 A → B → A 循环：遇到循环停止展开，statement 标 partiallyParsed，记内部诊断，不抛异常。

`<include refid="${dynamic}">` 目标不可静态确定：statement 标 UNRESOLVED，原因 DYNAMIC_INCLUDE。

#### `<property>` 替换（2026-09-07 真机反馈后重做）

`<include refid="x"><property name="k" value="v"/></include>` 的 `k` 在片段里以 `${k}` 出现。分两种，必须区别对待：

```text
① ${k} 只是拼 SQL          <sql> AND ${alias}.poi_id = #{poiId} </sql>
   → 参数名跟 ${} 无关，照常提取 poiId，${alias} 按替换型参数处理。什么都不用做。

② ${k} 拼在参数名里面      <sql> <if test="${prefix}poiId != null"> AND poi_id = #{${prefix}poiId} </if> </sql>
                           <include refid="cond"><property name="prefix" value="query."/></include>
   → 提取阶段拿不到任何参数名。第一版在这里静默失败：片段的参数一个都收不到，
     语句又没标 partiallyParsed，于是 Java 侧参数全被判成"未使用"，DAL-001 满屏误报。
```

②的正确做法是真的做替换。片段是"单文件事实"，而 `${k}` 的值只有引用点知道，所以：

```text
Index 阶段    include 存 refid + <property> 名值对（编码进同一个字符串，索引格式不变，VERSION 3）
              片段里"参数名内嵌 ${}"的原文整段存进 templates，不做提取
              判定用 ParameterTemplate.hasPlaceholderInsideParam：只有 #{...} 内部出现 ${ 才算，
              避免把 ①（满项目都是）平白降级成部分解析

Resolver 阶段 展开 include 时把 property 代入 templates，再按普通规则提取
              外层 property 继续对内层片段可见，同名以内层 include 为准
              代入后仍有 ${} 没给值 → statement 标 partiallyParsed，不报 DAL-001，进"无法解析"分组
```

原则还是那句：宁可不报，也不误报。

### 8.5 参数引用归一化

比较的是 **rootName**。

MyBatis（XML 与注解 SQL 相同）：

| 形态 | rootName | 说明 |
|---|---|---|
| `#{poiId}` | `poiId` | |
| `#{poiId, jdbcType=VARCHAR, typeHandler=...}` | `poiId` | 逗号后为属性 |
| `#{query.poiId}` | `query` | 取根 |
| `#{ids[0]}` | `ids` | |
| `${orderBy}` | `orderBy` | |
| `${alias.column}` | `alias` | |
| `${@com.example.Const@VALUE}` | 不计入 | OGNL 静态访问 |
| `<if test="...">`、`<when test="...">` | 见 8.6 | |
| `<foreach collection="ids" item="it" index="i">` | `ids` | `it`、`i` 在体内为局部名，不计入 |
| `<bind name="p" value="'%' + keyword + '%'">` | `keyword` | `p` 为局部名，不计入 |
| `<selectKey>` 内的引用 | 照常计入 | 访问同一参数对象 |
| `<where>` `<set>` `<trim>` `<choose>` `<otherwise>` | 无参数 | |
| `<resultMap>` `<association select>` `<collection>` | 不计入 | 不是参数使用 |
| `<![CDATA[ ... ]]>` 内文本 | 照常提取 | 实现时读 XmlText 全部子节点 |
| SQL 注释内 `-- #{x}` | 照常计入 | MyBatis 不识别注释，照样替换 |

iBatis 2（兼容）：

| 形态 | rootName |
|---|---|
| `#poiId#`、`#poiId:VARCHAR#` | `poiId` |
| `#query.poiId#` | `query` |
| `#ids[]#`、`#ids[].id#` | `ids` |
| `$orderBy$` | `orderBy` |
| 所有动态标签的 `property="x"` | `x` |
| `<isEqual property="a" compareProperty="b">` | `a`、`b` |
| `<iterate property="ids">` | `ids` |
| `parameterMap="pm"` | `pm` 下所有 `<parameter property>` |
| `<dynamic>` `<isParameterPresent>` | 无参数 |

`parameterClass` / `parameterType` 只是类型声明，不产生参数引用。

### 8.6 test 表达式提取

```text
按 OGNL 词法切出标识符
排除关键字        and or not null true false in instanceof
排除 . 之后的标识符   query.poiId 只取 query
排除 ( 之前的标识符   list.size() 中 size 是方法
排除 @ 开头的 token   静态访问
排除内置变量        _parameter _databaseId
排除局部名          foreach item/index、bind name
排除字符串字面量内部
```

```xml
<if test="query.poiId != null and ids.size() > 0 and status == 'ON' and _parameter != null">
```

提取：`query`、`ids`、`status`。

### 8.7 statement 合并与去重

| 情形 | 决策 |
|---|---|
| 同 id 不同 `databaseId` | 视为一条 statement，参数取并集，不报 DAL-006 |
| 同 namespace 分散在多个 XML | MyBatis 允许，按 fullId 合并，只有 fullId 重复才报 DAL-006 |
| 接口方法既有 XML 又有注解 SQL | DAL-006 |

---

## 9. Java 侧：调用识别

### 9.1 Mapper 接口（主路径）

**检查对象是接口方法声明，不是调用点。**

- 一个接口方法对应一条 statement，参数契约在签名处定死
- 调用点可能几十个，逐个检查重复报告
- 声明级不需要数据流追踪，置信度高

判定接口是 Mapper 接口，满足任一：

```text
MapperNamespaceIndex 中存在 key == 接口全限定名
接口带 @org.apache.ibatis.annotations.Mapper
接口任一方法带 MyBatis SQL 注解
```

`@MapperScan` 方式的项目没有 `@Mapper` 注解，靠前一条覆盖。

跳过的方法：

```text
default 方法、static 方法
从父接口继承的方法（MyBatis-Plus BaseMapper 等），只检查接口自身声明的方法
带 Provider 注解的方法（标 UNRESOLVED）
```

每个接口方法产出一个 `DaoInvocation`：

```text
kind         = MAPPER_METHOD
statementId  = 接口全限定名 + "." + 方法名
operation    = 由 XML 标签或注解类型决定
parameters   = 由方法签名决定，见 9.2
location     = 方法声明；参数问题定位到具体 PsiParameter
```

### 9.2 声明级参数（MyBatis 命名规则）

| 方法签名 | Java 侧参数 | 置信度 |
|---|---|---|
| `query(@Param("a") Long a, @Param("b") Long b)` | `a`、`b` | 高 |
| `query(Long a, Long b)` 无 `@Param` | 别名组 `{a, arg0, param1}`、`{b, arg1, param2}` | 中 |
| `query(@Param("q") OrderQuery q)` | `q`；Mapper 写 `#{q.poiId}` 取根匹配 | 高 |
| `query(OrderQuery q)` 单 Bean 无 `@Param` | 展开实体属性（字段 + getter/setter，含父类，排除 static/transient），每个属性与 SQL 路径比对；`address` 被 `#{address.city}` 覆盖算使用 | 低 |
| `query(@Param("q") OrderQuery q)` | 根 `q` 之外再按 `q.prop` 展开属性；根整体未用时只报根，不逐个报属性 | 根高 / 属性低 |

实体展开的前提：类型可解析、位于项目源码（库类型如 MyBatis-Plus `Page` 不展开）、不是 Map / 集合 / 数组 / 标量 / 枚举 / 接口。
这条是真机试用后改的：原方案"单 Bean 不检查"，试用反馈"参数是实体的扫不出哪个属性没用到"，因此改为低置信度逐属性报告，由使用者判断。设置里可关闭。
| `query(Map<String,Object> m)` 单 Map 无 `@Param` | 声明级不检查，转调用点，见 9.3 | — |
| `query(List<Long> ids)` 单集合无 `@Param` | 别名组 `{list, collection, arg0, param1}` | 中 |
| `query(Long[] ids)` 单数组无 `@Param` | 别名组 `{array, arg0, param1}` | 中 |
| `query(Long id)` 单标量无 `@Param` | 不检查；任意名字合法 | — |
| `RowBounds`、`ResultHandler` 类型参数 | 排除 | — |

别名组规则：Mapper 引用了组内任一名字即算该参数已使用。`arg0` 是否可用取决于编译时 `-parameters`，静态分析无法确定，三种一起算，宁可漏报。

### 9.3 调用点补充

接口方法只有一个 Map 或 Bean 参数时，声明级看不到 key，再去调用点做数据流追踪（第 10 节）：

```java
Map<String, Object> params = new HashMap<>();
params.put("poiId", poiId);
orderMapper.queryOrder(params);
```

调用点 receiver 类型解析到 Mapper 接口即可，不依赖变量名。同一 statement 的多个调用点各自独立报告，位置在各自的 `put` 行。

### 9.4 SqlSession 字符串调用

```java
sqlSession.selectList("com.example.order.dao.OrderMapper.queryOrder", params)
sqlSession.selectOne(...) / insert(...) / update(...) / delete(...)
sqlSessionTemplate.selectList(...)
```

判定依据：receiver 类型为 `SqlSession` / `SqlSessionTemplate` 及子类 + 方法名 + 第一参数为 String。
statementId 为第一参数，参数对象为第二实参，走第 10 节追踪。

### 9.5 iBatis 2（兼容）

```java
sqlMapClient.queryForList(...) / queryForObject(...) / insert(...) / update(...) / delete(...)
getSqlMapClientTemplate().queryForList(...)
```

receiver 类型为 `SqlMapClient` / `SqlMapClientTemplate` 及子类。处理同 9.4。

### 9.6 statementId 解析

接口方法：全限定名 + "." + 方法名，无需解析字符串。

字符串调用：

```java
selectList("com.example...OrderMapper.query", p);          // 字面量
private static final String NS = "com.example...OrderMapper.";
selectList(NS + "query", p);                                 // 常量拼接
private static final String QUERY = NS + "query";           // 常量引用
selectList(QUERY, p);
```

用 `PsiConstantEvaluationHelper` 求值。求不出（`selectList(getName(), p)`）标 UNRESOLVED，原因 STATEMENT_ID_DYNAMIC，不报。

MyBatis 允许短 id `selectList("query")`：全项目唯一时可解析，多候选报 DAL-006。

---

## 10. Java 侧：参数数据流

用于 9.3 / 9.4 / 9.5 中参数对象为 Map 或 Bean 的情形。以下示例用 `orderMapper.query(...)`，字符串调用处理完全相同。

### 10.1 Map

```java
Map<String, Object> params = new HashMap<>();
params.put("merchantId", merchantId);
params.put("poiId", poiId);
orderMapper.query(params);
```

| 形态 | 处理 |
|---|---|
| `put("k", v)`，k 为字面量或可求值常量 | 计入 `k` |
| `put(expr, v)`，k 不可求值 | 整个 Map 标 UNRESOLVED，原因 MAP_KEY_DYNAMIC |
| `Map.of(...)`、`Map.ofEntries(...)` | 计入所有 key |
| Guava `ImmutableMap.of(...)`、`ImmutableMap.builder().put(...).build()` | 同 `Map.of` |
| 双花括号 `new HashMap<>() {{ put("a", 1); }}` | 匿名类初始化块内的 put 归到该 Map |
| `Maps.newHashMap()`、`new LinkedHashMap<>()` | 构造方式无关，只看后续 put |
| `putAll(other)` | other 可追踪则合并，否则整个 Map 标 UNRESOLVED |
| `remove(...)`、`clear()` | 整个 Map 标 UNRESOLVED，原因 MAP_MUTATED |
| lambda 内的 put | 视为同一方法体内的 put，取并集 |
| Map 是方法入参 / 字段 / 接口方法返回值 | UNRESOLVED |

### 10.2 Bean

```java
OrderQuery query = new OrderQuery();
query.setMerchantId(merchantId);
query.setPoiId(poiId);
orderMapper.query(query);
```

| 形态 | 处理 |
|---|---|
| `setX(v)` | 按 JavaBeans 去 `set` 后首字母小写：`setPoiId` → `poiId` |
| `setURL(v)` | 前两字母大写保持原样：`URL`（`Introspector.decapitalize` 规则） |
| `setPoi_id(v)` | `poi_id` |
| 链式 `q.setA(a).setB(b)`（Lombok `@Accessors(chain=true)`） | 沿调用链向左找根变量，每个 `setX` 都算 |
| Lombok `@Setter` / `@Data` | 依赖 Lombok 插件补全 PSI；解析不到则 Bean 标 UNRESOLVED |
| Lombok `@Builder` 链 | V1 不支持，UNRESOLVED |
| Bean 无任何 setter 调用（直接传入参 Bean） | UNRESOLVED |

Bean 判定：类型不是 `Map` 子类型、不是 JDK 基础类型、且当前方法内有 setter 调用。

Bean 来源的 DAL-001 一律低置信度，理由见 6.1。

### 10.3 跨方法追踪

```java
Map<String, Object> params = buildParams(merchantId, poiId);
orderMapper.query(params);

private Map<String, Object> buildParams(Long merchantId, Long poiId) {
    Map<String, Object> m = new HashMap<>();
    m.put("merchantId", merchantId);
    m.put("poiId", poiId);
    return m;
}
```

进入被调方法的条件：

```text
能唯一解析到 PsiMethod
在当前 Project 源码中（不进 jar）
不是抽象方法、接口方法
```

被调方法内支持：

```text
Map / Bean 构造后 return
多个 return 分支，取并集
继续调用其他方法构造参数（递归，默认深度 3，可配）
```

返回后在当前方法继续补充：

```java
Map<String, Object> params = buildBaseParams(merchantId);
params.put("status", status);
orderMapper.query(params);          // 参数 = {merchantId, status}
```

停止条件（标 UNRESOLVED，不报）：

```text
深度超限                     DEPTH_EXCEEDED
调用链循环                   CYCLE
被调方法多实现               MULTI_IMPL
被调方法在库代码             LIBRARY_CODE
参数对象是入参 / 字段         METHOD_PARAM / FIELD
Map 被 putAll(不可追踪) / remove / clear   MAP_MUTATED
```

多分支取并集的理由：只要有一条路径会 put 某 key 而 Mapper 从不使用，该 put 就是无效代码，报 DAL-001 是准确的。

问题定位：在实际执行 put 的那一行，即使它在另一个方法里。报告附调用路径：

```text
路径：OrderDao.query() → OrderDao.buildParams()
```

多消费者：同一构造方法被多个 statement 消费，且只有部分 statement 未用某参数，则不报（该 put 对其他 statement 有效）。反向查找在后台任务中完成，不受编辑器响应约束。

### 10.4 Java 侧归一化汇总

| Java 形态 | rootName / 别名组 | 置信度 |
|---|---|---|
| `@Param("poiId") Long poiId` | `poiId` | 高 |
| 无 `@Param` 第 N 参数 `x` | `{x, arg(N-1), paramN}` | 中 |
| 无 `@Param` 单 Collection / 数组 | `{list, collection, ...}` / `{array, ...}` | 中 |
| `map.put("poiId", v)`、`Map.of("poiId", v)` | `poiId` | 高（方法内）/ 中（跨方法） |
| `map.put("query", bean)` | `query`，不下钻 | 同上 |
| `bean.setPoiId(v)` | `poiId` | 低 |
| 单标量 | 不检查 | — |
| 入参 / 字段 / 多实现 / 库代码 | UNRESOLVED | — |

---

## 11. 匹配与比较

```text
DAL-001 候选 = Java 侧 rootName（含别名组）集合 − Mapper 侧 rootName 集合
```

- 别名组内任一名字被 Mapper 引用，即视为已使用。
- 精确匹配，大小写敏感。
- Java 侧名字找不到、但 Mapper 侧存在仅大小写不同的名字：仍报 DAL-001，备注"Mapper 中存在 'poiid'，疑似大小写不一致"。
- Java 侧声明了 `dataStatuses`，而 Mapper 里写的是 `query.dataStatuses`（同名但带对象前缀）：仍报 DAL-001，备注说明两种可能——该值本就该通过 `query` 传入（此处 `@Param` 多余），或 `@Param` 名字与 SQL 对不上（那样 SQL 里那个条件永远不成立）。反向情形（Java 侧是 `query.x`，Mapper 里直接写 `x`）同样给备注。2026-09-07 真机反馈：不给这条备注，报告看起来像"明明用了却说没用"。
- 命中忽略配置（第 15 节）的不进入候选。
- statement 为 partiallyParsed 时不报 DAL-001，只做 DAL-005 / DAL-006。
- 单标量参数不报 DAL-001。

---

## 12. 模块可见性

### 12.1 使用 IDEA Project Model

不自己解析 Maven Reactor。用 `ProjectFileIndex`、`ModuleRootManager`、`ProjectRootManager` 取得：当前文件所属 Module、Source / Resource Root、直接依赖、传递依赖。

### 12.2 候选优先级

```text
当前 Module → 直接依赖 Module → 传递依赖 Module
```

"当前 Module"对接口方法指接口所在模块，对调用点指调用代码所在模块。

默认不全项目匹配。同级候选出现多个 → DAL-006。

### 12.3 严格 / 兼容模式

```text
● 严格模块依赖     只查当前 + 依赖 Module
○ 整项目兼容模式   找不到时允许全 Project fallback；多候选仍报 DAL-006，不猜
```

### 12.4 test root

test 资源目录下的 XML 只对 test 源码可见。main 代码解析 statement 时排除 test root，避免测试 Mapper 造成 DAL-006。

### 12.5 多数据库变体目录

`mapper/mysql/OrderMapper.xml` 与 `mapper/oracle/OrderMapper.xml` 各一份：报 DAL-006，备注"可能为多数据库变体"。提供设置项"忽略路径模式"整体排除某目录。

### 12.6 依赖 jar 中的 statement

library roots 已索引。仍找不到时，DAL-005 降为中置信度，备注"可能定义在未索引的依赖中"。

---

## 13. 检查执行流程

### 13.1 入口

```text
编辑器右键            检查当前文件
Project 视图右键      检查所选文件 / 目录 / Module
Tools 菜单            检查整个项目
报告窗口工具栏         重新检查 / 更换范围
Analyze → Inspect Code   勾选本插件的 GlobalInspectionTool（批量模式）
```

不注册 LocalInspectionTool。

### 13.2 Dumb Mode

```text
触发时 DumbService.isDumb()
   → 提示"IDEA 正在建立索引，请索引完成后再检查。"
   → 不启动任务，不排队
```

Ctrl+Click 导航在 Dumb Mode 下静默返回空。原则：宁可不跑，不能出 `IndexNotReadyException`。

### 13.3 执行

```text
Task.Backgroundable（可取消，进度 = 已处理 / 总数）
   ↓
发现阶段：定位 Mapper 接口与字符串调用点（13.4）
   ↓
按文件分片，每片 ReadAction.nonBlocking，及时让出读锁
   ↓
每个 DaoInvocation：定位 statement → 取 Mapper 参数 → 取 Java 参数 → 差集 → 抑制过滤 → ContractIssue
   ↓
汇总 CheckResult
   ↓
EDT 刷新报告窗口
```

取消：任务终止，报告保留上一次结果。

### 13.4 发现策略

扫描量与 Mapper 数量成正比，不与 Java 文件总数成正比：

```text
Mapper 接口
  MapperNamespaceIndex 全部 key → JavaPsiFacade.findClass
  ∪ AnnotatedElementsSearch(@Mapper)
  ∪ 带 MyBatis SQL 注解的方法所在接口

字符串调用点
  对 SqlSession / SqlSessionTemplate / SqlMapClient / SqlMapClientTemplate 的目标方法
  做 MethodReferencesSearch
```

范围为"当前文件"时直接遍历该文件 PSI。

### 13.5 运行级缓存

所有缓存随一次任务创建、任务结束销毁，不做跨运行缓存：

```text
CheckRunContext
 ├ resolvedStatements   fullId → 展开 include 后的参数集合
 ├ fragmentParams       fullId → 片段参数
 ├ parameterMaps        fullId → property 列表
 ├ builderResults       PsiMethod → 跨方法追踪结果
 ├ moduleVisibility     Module → 可见 Module 集合
 └ statistics
```

用普通 `HashMap` 即可。理由：手动触发不需要为编辑器响应攒缓存；避免 `PsiModificationTracker` 失效策略复杂度；结果永远与触发那一刻的代码一致。

---

## 14. 报告

### 14.1 工具窗口

窗口名 `MyBatis Mapper Checker`。

统计条：

```text
范围：整个项目   Mapper 接口：46   DAO 调用：328   成功解析：317   无法解析：11   已抑制：4   问题：23（高 15 / 中 5 / 低 3）
```

树形表格，Module → 文件 → 问题：

| 列 | 内容 |
|---|---|
| 规则 | DAL-001 / DAL-005 / DAL-006 |
| 参数 | poiId |
| statement | com.example.order.dao.OrderMapper.queryOrder |
| 位置 | OrderMapper.java:18 |
| 置信度 | 高 / 中 / 低 |
| 备注 | 空或说明文字 |

交互：

```text
双击            跳到 Java 位置（@Param 参数 / put 行 / 方法名）
右键 跳转 Mapper  打开 XML 定位 statement，或接口方法上的注解
右键 忽略此处     写入"statement#参数"组合抑制，从报告移除
右键 忽略参数     全局忽略该参数名
右键 忽略 statement
右键 标记已确认    仅本次报告，不持久化
工具栏 过滤       规则 / 置信度 / Module
工具栏 导出       Markdown / CSV，默认到项目根 mybatis-mapper-checker-report.md
工具栏 重新检查 / 更换范围
```

### 14.2 文案

```text
参数 'poiId' 已声明在 'OrderMapper.queryOrder'，但对应 Mapper SQL 未使用该参数。

规则：DAL-001
Mapper：order-dao/src/main/resources/mapper/OrderMapper.xml
statement：com.example.order.dao.OrderMapper.queryOrder
置信度：高
```

调用点 Map 场景：`参数 'poiId' 已传入 'OrderMapper.queryOrder'，但对应 Mapper SQL 未使用该参数。`

跨方法追加：`路径：OrderDao.query() → OrderDao.buildParams()`，置信度：中。

Bean 追加：`备注：参数来自 Bean setter，该属性可能另有用途，请人工确认。`，置信度：低。

DAL-005：`'OrderMapper.queryOrder' 未找到对应的 Mapper statement 或 SQL 注解。`

DAL-006：`'OrderMapper.queryOrder' 匹配到多个 Mapper statement，无法确定实际调用目标。` 并列出各候选文件。

### 14.3 无法解析分组

单独分组列出所有 `UnresolvedInvocation`，注明原因（入参 / 字段 / 多实现 / 深度超限 / Provider / 动态 include / 动态 statementId），不算问题，让使用者知道哪些调用没被覆盖。

### 14.4 其他

- 项目内没有任何 Mapper：显示"未发现 Mapper 接口或 Mapper XML"，不算错误。
- 报告不持久化，IDE 重启后为空。
- 记住上次选择的范围。
- 检查运行中编辑文件不崩溃，位置用 `SmartPsiElementPointer` 保存，修改后仍可跳转。
- 不做：编辑器波浪线、gutter 报错图标、状态栏常驻提示。

### 14.5 责任人（真机反馈 2026-09-07 加入）

问题扫出来一批，想直接按人分派认领。走平台通用 VCS API（`AbstractVcs.getAnnotationProvider()`），不认哪个具体 VCS——Git4Idea 装了就有用，没装就老老实实什么都不显示，不装作能看穿不存在的版本库。`GitBlameService`（Project 级服务，实现 `Disposable`）：

```text
blameLineCached(file, line)   只读缓存，不触发新的 annotate，EDT 安全，渲染树用这个
blameLine(file, line, indicator)  后台线程调用，真正触发 annotate（可能起子进程），缓存结果
warm(files, indicator)        批量预热，工具栏"显示责任人"打开时后台跑一遍
```

`annotate()` 结果按 `VirtualFile` + `modificationStamp` 缓存，文件改动后自动失效重算；Project 关闭时 `dispose()` 释放全部 `FileAnnotation`。"作者"不是 `FileAnnotation` 的一等方法，要在 `getAspects()` 里按 `LineAnnotationAspect.AUTHOR` 找。

工具栏"显示责任人"（默认关）：打开后台预热当前列表涉及的全部文件，完成后 `tree.repaint()`，渲染器只读缓存追加"作者  日期"，没有就什么都不加，不占位置、不显示"加载中"。右键"查看该处提交信息"：单文件单行，后台起个小进度条即时查，不依赖开关状态。

依赖 `com.intellij.modules.vcs`（`plugin.xml` 新增一条 `<depends>`）——该模块在所有主流 JetBrains IDE（含 Community）里都在，与已有的 `com.intellij.modules.java` / `.xml` 一样按硬依赖处理，不做可选依赖的复杂度。

**真机反馈（2026-09-08）修的一个线程崩溃**：`appendBlame` 最初直接用 `ri.javaElement().getContainingFile().getVirtualFile()` 拿文件，在树的展开 / 绘制过程里报
`Read access is allowed from inside read-action only`——渲染发生在 Swing 布局线程上，没有 read action，碰 `PsiElement.getContainingFile()`（哪怕只读）会被 2024.2+ 的线程模型断言拦下来。改法沿用 `ReportedIssue.moduleName` 那次修复定下的同一条规矩：**渲染 / 预热阶段只准碰扫描时已经算好的纯数据，不准碰 PSI**——`appendBlame`、`addFileOf`（预热用的文件收集）、`viewCommit`（右键查看提交信息）统一改成从 `ContractIssue.primaryLocation()`（`filePath` + `line`，纯 `String`/`int`，扫描时的 read action 里已经算好）取文件，`VirtualFile` 用 `CheckRunContext.findFileForNavigation` 查（VFS 查找，不是 PSI，EDT 上一直安全，`navigateToJava` 的无法解析分支早就这么用）。

同一天紧接着又报了一次：这次是**双击跳转**（`navigateToJava` → `ri.javaElement()`），同一根因，只是触发点不同——`javaElement()` 内部 resolve `SmartPsiElementPointer` 时会调 `isValid()`，这一步本身就要 read access，在 EDT 上裸调同样会崩。这次不能像 blame 那样绕开 PSI（导航需要精确到字符偏移，`primaryLocation` 的偏移是扫描时的静态快照，文件被编辑过后会跟实际位置有偏差，不如 `SmartPsiElementPointer` 准），改成把 resolve / isValid / getContainingFile / getTextRange 整段包进一个 `ReadAction.run`，只把结果（`VirtualFile` + 偏移）带出来，真正的 `navigate()`（UI 操作）留在 read action 外面执行——`navigateToJava` / `navigateToMapper` 统一走这个 `navigateToPointer` helper。"跳转到 Mapper"菜单项的 `update()`（EDT 上频繁调用）也顺手改成只判断指针是否为 null，不 resolve。

### 14.6 调用链（真机反馈 2026-09-08 加入）

想知道一个参数最初是从哪个入口（Controller / 定时任务 / 测试……）一路传下来的。与 8.3 的"跨方法追踪"（`callPath`，追的是参数对象怎么被一路构造出来，方向向下、随扫描一起算）不同，这个是反过来：从问题所在的方法开始**向上**找"谁调用了它"，只在报告右键"查看调用链"时按需算，不进批量扫描——引用搜索一层层摊开，代价和扫一遍报告完全不是一个量级。

`CallChainFinder`：

```text
build(target, indicator)   以 target 为根，递归 MethodReferencesSearch 向上找调用者，构成一棵树
  深度上限 6，每层最多展开 5 个不同调用方法（同一方法多处调用只算一次），
  单方法引用搜索本身也设 300 条上限，总节点数上限 200——任何一处超限都在渲染里明说，不静默截断
  同一条链上出现过的方法判定为循环调用，停止展开，不会死循环 / 栈溢出
  展开到某个方法确实一个调用者都没有：视为入口点（Controller 方法 / 定时任务 / 测试 / 反射调用 /
  未被使用，四种情况分不出来，如实写"未找到调用者"，不猜是哪种）
render(root)                渲染成缩进树文本（读法与 IDE 自带 Call Hierarchy 一致：方法在上，调用者依次缩进在下）
```

只看项目源码（`GlobalSearchScope.projectScope`），不进依赖 jar——调用者只可能是用户自己的代码。锚点解析：`PsiTreeUtil.getParentOfType(anchor, PsiMethod.class)`，对参数锚点（声明参数、实体属性）和方法体内锚点（跨方法数据流、DAL-005/006 的方法名标识符）都能正确找到所在方法，因此这个动作对**任意规则**的问题都能用，不限于 DAL-001。

必须在 ReadAction 内调用（`MethodReferencesSearch` 是 PSI 操作）；报告面板 `viewCallChain` 用 `ProgressManager.runProcessWithProgressSynchronously` 包一层 `ReadAction.run`，弹出的 `CallChainDialog` 是非模态的等宽字体只读文本框，方便对照报告和源码一起看。

---

## 15. 抑制与配置

### 15.1 三种抑制

```text
1. 组合抑制    设置中记录 "com.example.order.dao.OrderMapper.queryOrder#poiId"，只抑制这一个组合
              报告右键"忽略此处"直接写入
2. 注解抑制    @SuppressWarnings("DAL-001")，可放在接口方法、参数、DAO 方法上
3. 行注释      // mapper-checker: ignore   放在 put 行或参数所在行
```

三种方式在统计条里合计为"已抑制：N"。

### 15.2 Project 级设置

持久化到 `.idea/mybatis-mapper-checker.xml`，可提交版本库。

```text
忽略参数（支持通配 page* / *Sort）
忽略内置分页 / 排序参数名单（默认开）
展开实体参数逐属性检查（默认开，低置信度）
上游赋值分析（默认开，见 6.1B）
忽略 statement
组合抑制列表
忽略路径模式（如 **/mapper/oracle/**）
跨方法追踪深度（默认 3）
严格 / 兼容模式（默认严格）
规则启停
规则级别
gutter icon 开关（默认关）
报告默认范围（默认整个项目）
Query 类后缀（默认 Query）
拷贝方法源参数位置（fqn#method=index，每行一条，与内置表合并）
模板 statement id 列表（DAL-003 适用范围）
在编辑器里实时提示纯 Java 规则（默认关，见 15.4）
```

### 15.3 国际化

文案全部走 `MapperCheckerBundle`，默认 zh_CN。后续加英文只补一个 properties。

### 15.4 团队豁免文件与实时提示（2026-09-07 加入）

**豁免 ≠ 抑制。** 15.1 的三种抑制是个人 / 项目级"别再烦我"，不留痕；豁免是团队级"我们确认过了"，进版本库、有人名日期、报告里仍然可见。

```text
文件   .binding-scan-ignore.yml，放项目根或任一 Module 内容根，全部生效
格式   - rule: DAL-010                       # 可省，省略则匹配该 target 的全部规则
         target: com.x.OrderQuery#poiId       # 与组合抑制 key 相同：owner#name / statement#param；只写 statement 匹配它的所有参数
         reason: 由拦截器注入
         by: 张三
         at: 2026-09-07
校验   reason / by / at 缺一条即无效：不生效、统计条与 Markdown 里提示"N 条豁免记录缺 reason / by / at 未生效"
写入   报告右键"豁免此处"：弹窗填理由，by = 当前系统用户，at = 今天，追加到问题所在 Module 的 yml（没有则建在项目根）
展示   报告树"已豁免（N）"分组，行内显示 by / at / reason，详情含记录文件与行号；Markdown 单独一节；CSV 首列"状态"区分 问题 / 已豁免
解析   自写 ExemptionFileParser，只支持"列表 + 平铺键值"，引号内的 # 与 : 保留，不引入 YAML 库
```

实时提示：`JavaRulesLocalInspection`（`localInspection`，shortName `MyBatisMapperJavaRules`）只跑四条纯 Java 规则 DAL-004 / 020 / 022 / 030，按元素访问（`visitMethodCallExpression` / `visitMethod`），不做引用搜索。设置项默认关；关时 `buildVisitor` 返回空访问器，编辑器零干扰，与 1.4 的"手动触发"原则不冲突。抑制与豁免同样生效。

---

## 16. 导航

Mapper 接口方法：

```java
List<Order> queryOrder(@Param("poiId") Long poiId);
            ^^^^^^^^^^   Ctrl/Cmd + Click → OrderMapper.xml <select id="queryOrder">
```

字符串调用：

```java
sqlSession.selectList("com.example.order.dao.OrderMapper.queryOrder", params);
                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

gutter icon 默认关闭。导航是被动能力不算干扰，但 gutter icon 在每个 DAO 行常驻，对喜欢干净编辑器的人是噪音。

检测到 MyBatisX 等已提供接口方法双向跳转的插件时，本插件的接口方法导航让位，只保留字符串调用导航，避免重复菜单项。

反向导航（statement → 调用处）留后续。

---

## 17. 性能设计

手动触发后，要求从"毫秒级不卡编辑器"变为"后台跑完、可取消、不冻结 UI"。

禁止：

```text
在 EDT 上执行检查
长时间持有一个 ReadAction
用 Files.walk() 代替 FileBasedIndex
遍历所有 Java 文件判断是否 Mapper
```

单个 DaoInvocation 理想开销：

```text
方法 PSI + 1 次 Index 查询 + 少量 include 展开 + （跨方法时）有限次 PsiMethod 解析
```

关键点：FileBasedIndex 定位；`ReadAction.nonBlocking` 分片；`ProgressIndicator` 取消；运行级缓存；`SmartPsiElementPointer` 保存报告位置。

### 17.1 真机反馈：扫描太慢（2026-09-07）

大项目上第一版慢在四处，都是"看似一次搜索，实际乘以了几千"的地方：

| 慢在哪 | 原因 | 改法 |
|---|---|---|
| 字符串调用发现 | 对 SqlSession / SqlSessionTemplate / SqlMapClient(Template) 的**每个重载**各做一次全项目引用搜索，几十次；`update` / `insert` / `delete` 是满项目都有的词，平台要把每一处都解引用 | 同一个词只查一次索引拿候选文件，每个文件只解析一次，命中判定仍走 `StringCallInvocationExtractor.extract`（先比方法名、再看首参是不是 String，最后才解引用）。`insert` / `update` / `delete` / `select` 不作种子词，但候选文件里照常识别 |
| DAL-010 判断"有没有人 set" | 逐实体逐属性做全项目引用搜索：`setStatus` 这类名字满项目都是，且大多解析到别的类，`findFirst` 也要翻很多文件 | 扫描 Java 文件时顺手登记 `x.setFoo(...)` → `实体#属性` 查表；查不到的少数属性才回退引用搜索，且只有 DAL-011 开启时才需要区分 |
| Query 类清单 | `getAllClassNames()` 把依赖 jar 的名字一起给出来，Hibernate / JPA 的 `CriteriaQuery`、`NativeQuery` 都命中 Query 后缀，拿去做全项目词搜索纯浪费；且 DAL-021 与候选文件各算一遍 | `QueryClassIndex` 只保留源码里真实存在的 Query 类，一次运行只算一次，两处共用 |
| 逐条提交读操作 | 每个接口 / 调用点 / 文件各提交一次 `ReadAction.nonBlocking(...).executeSynchronously()`，几千次线程往返 | 按 50ms 时间片批量处理；被写操作打断时只重跑当前这一小批 |

另外 `BeanPropertyCollector.collect` 走 `CachedValuesManager` 缓存（同一实体会被多条 statement、多条规则反复问）；四条纯 Java 规则与 DAL-010/011 全关时，整个 Java 文件遍历直接跳过。

代价（已接受）：只用 `insert` / `update` / `delete` 且既不提到 receiver 类型名、也没有任何 `selectXxx` / `queryForXxx` 的 DAO 文件会漏掉；关掉 DAL-011 时，set 发生在扫描范围之外的属性不再报 DAL-010。

---

## 18. 包结构

```text
com.mapperchecker
│
├── core                                  （checker-core）
│   ├── model
│   │   ├── DaoInvocation / MapperStatement / ParameterReference
│   │   ├── ContractIssue / UnresolvedInvocation / CheckResult
│   │   ├── RuleId / Severity / Confidence / SourceLocation
│   ├── naming
│   │   ├── ParameterNameNormalizer       setX → x，Introspector 规则
│   │   ├── AliasGroupBuilder             argN / paramN / list / array
│   │   └── OgnlIdentifierExtractor       test 表达式提取
│   ├── contract
│   │   ├── ParameterContractEngine       差集 + 别名组匹配 + 大小写提示
│   │   └── ConfidenceResolver
│   └── rule
│       ├── UnusedParameterRule           DAL-001
│       ├── StatementNotFoundRule         DAL-005
│       └── AmbiguousStatementRule        DAL-006
│
└── idea                                  （checker-idea）
    ├── action
    │   ├── CheckCurrentFileAction / CheckSelectionAction / CheckProjectAction
    ├── run
    │   ├── ContractCheckTask             Task.Backgroundable
    │   ├── CheckRunContext               运行级缓存
    │   └── InvocationDiscovery           13.4 发现策略
    ├── index
    │   ├── MapperStatementIndex / MapperNamespaceIndex
    │   ├── SqlFragmentIndex / ParameterMapIndex
    │   └── MapperXmlInputFilter
    ├── mapper
    │   ├── MyBatisXmlParser / IBatisXmlParser
    │   ├── AnnotationSqlParser
    │   ├── IncludeResolver
    │   └── MapperStatementResolver       Index + include + parameterMap → 参数集合
    ├── java
    │   ├── MapperInterfaceDetector
    │   ├── MapperMethodInvocationExtractor
    │   ├── MethodSignatureParameterResolver
    │   ├── StringCallInvocationExtractor SqlSession / SqlMapClient
    │   ├── StatementIdResolver
    │   ├── JavaParameterResolver         Map / Bean 数据流
    │   └── ParameterBuilderMethodResolver 跨方法
    ├── module
    │   └── ModuleVisibilityResolver
    ├── report
    │   ├── CheckReportToolWindowFactory / CheckReportPanel / CheckReportModel
    │   └── ReportExporter                Markdown / CSV
    ├── inspection
    │   └── MapperContractGlobalInspection
    ├── navigation
    │   ├── MapperGotoDeclarationHandler
    │   └── MapperLineMarkerProvider
    ├── suppress
    │   └── SuppressionMatcher            组合 / 注解 / 行注释
    └── settings
        ├── MapperCheckerSettings
        └── MapperCheckerConfigurable
```

---

## 19. 测试

### 19.1 分层

```text
checker-core     普通 JUnit：归一化、别名组、OGNL 提取、差集、置信度
Light Test       Java + XML fixture，直接调用检查引擎，断言 ContractIssue 列表逐字段
Heavy Test       多 Module 可见性
Inspect Code     myFixture.testInspection() 冒烟
```

不用 `checkHighlighting()`，插件不产生编辑器高亮。

Heavy Test 模拟：

```text
parent
├ module-common     Common.xml（<sql> 片段）
├ module-order      OrderMapper.java + OrderMapper.xml
├ module-app        依赖 order
└ module-sibling    与 order 无依赖，含同 fullId statement
```

### 19.2 测试矩阵

**Mapper 接口**

| 场景 | 预期 |
|---|---|
| `@Param("poiId")` 声明，XML 未用 | DAL-001，高，位置在 PsiParameter |
| `@Param` 全部使用 | 无 |
| 无 `@Param` 双参数，XML 用 `#{param2}` 未用第一个 | DAL-001（第一个），中 |
| 无 `@Param` 双参数，XML 用 `#{arg0}` `#{arg1}` | 无 |
| 单 Bean 无 `@Param`，XML 只用 `#{merchantId}` | 其余属性 DAL-001，低，位置在实体字段 |
| 单 Bean 含 pageNum / pageSize / orderBy 属性 | 内置分页名单忽略，不报 |
| 单 Bean 属性 `address`，XML `#{address.city}` | 匹配，无问题 |
| `@Param("q") Bean`，XML `#{q.merchantId}` | `q.poiId` 等未用属性 DAL-001，低 |
| `@Param("q") Bean` 整体未用 | 只报 `q`（高），不逐个报属性 |
| 单 `Object` / `Date` 参数 | 库类型不展开，不检查 |
| 单 Map 无 `@Param`，调用点 put 多余 key | DAL-001，位置在 put 行 |
| 单 `List<Long>`，XML `<foreach collection="list">` | 无 |
| 单 `List<Long>`，XML 未用任何别名 | DAL-001，中 |
| 含 `RowBounds` | 排除 |
| 方法无 XML 无注解 | DAL-005，位置在方法名 |
| 方法既有 XML 又有 `@Select` | DAL-006 |
| `@Select` 未用 `@Param` 参数 | DAL-001 |
| `@Select` 含 `<script><if test>` | 正确提取 |
| `@Select` 值为常量拼接 | 求值后正确提取 |
| `@Select` 含不可求值片段 | partiallyParsed，不报 DAL-001 |
| `@SelectProvider` | 不报，列入无法解析 |
| `default` / `static` 方法 | 跳过 |
| 继承自 `BaseMapper` 的方法 | 跳过 |
| 接口重载同名方法 | DAL-006 |
| `@MapperScan` 项目（无 `@Mapper` 注解） | 靠 namespace 索引正确判定 |

**字符串调用**

| 场景 | 预期 |
|---|---|
| `sqlSession.selectList("全限定.id", map)` | 与 Map 场景同规则 |
| 短 id 全项目唯一 | 可解析 |
| 短 id 多候选 | DAL-006 |
| 静态常量 / 拼接 statementId | 正确 |
| `selectList(getName(), p)` | 无法解析，不报 |
| iBatis `queryForList("Order.query", map)` | 与 MyBatis 同规则 |

**参数数据流**

| 场景 | 预期 |
|---|---|
| Map put 遗漏 | DAL-001，高 |
| `Map.of` / `ImmutableMap.of` / 双花括号 | 正确 |
| put key 为常量 | 正确 |
| put key 不可求值 | 无法解析 |
| `putAll(可追踪)` | 合并 |
| `putAll(不可追踪)` / `remove` / `clear` | 无法解析 |
| Bean setter 遗漏 | DAL-001，低，备注 |
| `setURL` ↔ `#{URL}` | 匹配 |
| 链式 setter | 每个都算 |
| Lombok `@Builder` | 无法解析 |
| 入参 Bean 无 setter | 无法解析 |
| 同类私有方法构造后遗漏 | DAL-001，中，位置在构造方法 put，附路径 |
| 其他类静态方法构造 | DAL-001 |
| 多 return 分支某分支遗漏 | DAL-001 |
| 构造后当前方法再 put | 合并 |
| 构造方法被多 statement 消费仅部分未用 | 无 |
| 深度超限 / 互相递归 | 无法解析，不抛异常 |
| 接口方法多实现 / 入参 / 字段 | 无法解析 |

**Mapper 解析**

| 场景 | 预期 |
|---|---|
| `#{}` `${}` `#x#` `$x$` | 正确 |
| `#{query.poiId}` ↔ Java `query` | 匹配 |
| `<foreach item="it">` + `#{it}` | `it` 不计入 |
| `<bind name="p">` + `#{p}` | `p` 不计入 |
| `<if test="q.poiId != null and list.size() > 0">` | 提取 `q`、`list` |
| `${@Const@V}` / `_parameter` | 不计入 |
| CDATA 内 `#{}` | 提取 |
| `<selectKey>` 内 `#{}` | 计入 |
| `<resultMap>` / `<association select>` | 不计入 |
| `<include>` / 跨 namespace include / 链式 include | 正确展开 |
| include 循环 | 不死循环，partiallyParsed |
| `<include refid="${x}">` | 无法解析 |
| iBatis `parameterMap` + `?` | 正确 |
| 同 id 不同 databaseId | 合并，不报 DAL-006 |
| 同 namespace 多文件 | 合并 |
| Java `poiId` vs Mapper `poiid` | DAL-001，备注大小写 |

**模块与运行**

| 场景 | 预期 |
|---|---|
| 同 Module / 依赖 Module / 传递依赖 | 正确 |
| 不可见 sibling 同 fullId | 不误匹配 |
| 兼容模式 fallback | 可解析；多候选仍 DAL-006 |
| test root XML 对 main 不可见 | 不报 DAL-006 |
| 多数据库变体目录 | DAL-006 + 备注；忽略路径后消失 |
| jar 内 XML | 可定位 |
| 忽略参数 / statement / 组合 / `@SuppressWarnings` / 行注释 | 不报，计入已抑制 |
| Dumb Mode 触发 | 提示，不启动，不抛异常 |
| 中途取消 | 保留上次结果 |
| 运行中编辑文件 | 不崩溃，可跳转 |
| 导出 Markdown | 与窗口一致 |
| 无 Mapper 项目 | 提示未发现 |

---

## 20. 实施阶段

| 阶段 | 内容 | 产出 |
|---|---|---|
| 1 骨架 | Gradle Kotlin DSL、plugin.xml、两模块、Sandbox、Plugin Verifier、Bundle | 空插件可运行 |
| 2 索引 | 四个 Index、输入过滤、library roots、test root 标记 | 可查 statement |
| 3 Mapper 解析 | MyBatis XML、注解 SQL、iBatis XML、include、parameterMap、归一化、OGNL | 任意 statement → 参数集合 |
| 4 Java 解析 | Mapper 接口判定、签名别名组、字符串调用、statementId 求值 | 任意 DaoInvocation |
| 5 模块可见性 | 优先级、严格/兼容、test root、歧义 | 候选列表 |
| 6 DAL-005 / DAL-006 | 定位规则 | 定位准确 |
| 7 DAL-001 声明级 | `@Param` / 别名组 / 差集 / 置信度 / 忽略 | 主路径可用 |
| 8 DAL-001 数据流 | Map / Bean / 链式 / Guava / 跨方法 / 多消费者 | 补充路径可用 |
| 9 触发与报告 | 三个 Action、后台任务、运行级缓存、工具窗口、导出、GlobalInspectionTool | 端到端可用 |
| 10 抑制与设置 | 三种抑制、设置页、持久化 | 可配置 |
| 11 导航 | Ctrl+Click、gutter（默认关）、MyBatisX 让位 | |
| 12 稳定性 | Heavy Test、Dumb Mode、取消、大项目耗时、Plugin Verifier 全版本 | 可发布 |

阶段 6、7 完成后即可在真实项目上试用并收集误报。

---

## 21. 验收标准

```text
打开 Maven 父项目，IDEA 导入多个子模块
   ↓
Tools 菜单触发整个项目检查，后台运行，有进度，可取消
   ↓
跨 Maven Module 找到 Mapper XML 与注解 SQL
   ↓
发现 @Param 声明未使用、Map put 未使用、跨方法构造未使用
   ↓
报告按 Module / 文件分组，带置信度与中文备注，无法解析单独分组
   ↓
双击跳 Java，右键跳 Mapper
   ↓
Analyze → Inspect Code 得到同样结果
```

并满足：

```text
编辑器中没有任何实时波浪线或提示
索引期间触发只给提示，不报错
重新检查后报告反映最新代码
检查期间编辑器可正常使用
正常使用的参数不误报
Bean setter 场景明确标低置信度
sibling Module 不误匹配；同 fullId 不擅自猜测
databaseId 变体、test root、多文件同 namespace 不误报 DAL-006
无法解析的调用在报告中可见
```

---

## 22. 工程原则

1. **准确率优先于覆盖率**：无法证明就不报；能报的标清置信度让人判断。
2. **Index / Resolver / Rule / 展示分层**：Index 是单文件事实，Resolver 是跨文件关系，Rule 是业务判断，报告是展示。
3. **core 不碰 IDEA API**。
4. **尊重 Maven 模块结构**，不全项目乱匹配。
5. **不自动改 SQL**，只做跳转和配置。
6. **手动触发，零干扰**。
7. **报告可疑而非断言错误**。
8. **面向开发者的一切文案用中文**：注释、规则描述、报告、设置、测试说明、日志。保留英文：规则 ID、类名、Extension Point、枚举。

---

## 23. 后续扩展

```text
Lombok @Builder 链式构造
@SelectProvider 的 Provider 方法静态分析
MyBatis-Plus Wrapper 条件构造器
方法入参反向追溯调用方
跨类继承 / 接口多实现的参数追踪
Bean 嵌套属性校验
statement → 调用处反向导航
可选的编辑器实时模式（默认关闭）
UPDATE / DELETE 无 WHERE 检查
${} 注入风险提示
Kotlin / UAST
checker-cli → GitLab CI
checker-sonar
```

长期结构：

```text
              checker-core
                   │
      ┌────────────┼────────────┐
      ▼            ▼            ▼
checker-idea  checker-cli  checker-sonar
```

当前阶段只打磨一条链：**MyBatis Mapper 接口 / DAO 调用 → statement → 参数契约，iBatis 2 复用同一套模型作为兼容路径。**
