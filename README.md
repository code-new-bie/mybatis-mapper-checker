# MyBatis Mapper Checker

IDEA 插件：检查 MyBatis 项目里 Java 侧声明或传入的参数，与 Mapper SQL 实际使用的参数是否一致。手动触发，汇总成报告。兼容 iBatis 2。

## 能发现什么

```java
public interface OrderMapper {
    List<Order> queryOrder(@Param("merchantId") Long merchantId,
                           @Param("poiId") Long poiId,          // ← XML 里没用到
                           @Param("status") String status);
}
```

```xml
<select id="queryOrder">
    SELECT * FROM orders WHERE merchant_id = #{merchantId} AND status = #{status}
</select>
```

规则编号沿用团队规范《开发规范：Query 与 Mapper 绑定》，11 条全部覆盖：

| 规则 | 含义 | 默认级别 |
|---|---|---|
| DAL-001 | 参数已声明或传入，但 Mapper SQL 未使用 | Warning |
| DAL-002 | Mapper 引用了实体类里不存在的属性（改字段名未同步 XML） | Error |
| DAL-003 | 模板语句里 `<if test="a">` 块内绑定的却是 `#{b}` | Error |
| DAL-004 | `copyProperties` 参数顺序写反，空对象被当作拷贝源 | Error |
| DAL-005 | statement 不存在（接口方法既无 XML 也无 SQL 注解） | Error |
| DAL-006 | statement 存在多个候选，无法确定目标 | Warning |
| DAL-010 | 调用方 set 了字段，但 Query 类关联的所有语句都不引用它 | Warning |
| DAL-011 | Query 类死字段：既无人 set 也无语句引用 | Weak Warning |
| DAL-020 | transform / convert 方法内使用反射拷贝 | Warning |
| DAL-021 | 跨模块同名 Query 类 | Warning |
| DAL-022 | DAO 方法的 Query 参数未注解为 `@Param("query")` | Weak Warning |
| DAL-030 | 跨层转换 `setA(x.getB())` 改了字段名（拼写相近 / 单复数） | Warning |

DAL-010 / 011 / 021 需要全局信息，只在 Module 或整项目范围运行。

覆盖的 Java 写法：Mapper 接口 `@Param` / 无 `@Param` 别名组、实体参数逐属性比对（含 `#{q.prop}` 与 `<if test="q.prop">` 路径）、单 Map 参数到调用点的 `put` 追踪、跨方法构造的参数对象、Bean setter、`SqlSession` 与 iBatis 2 `SqlMapClient` 字符串调用。

内置分页 / 排序参数名单（pageNum、pageSize、offset、limit、orderBy 等）默认忽略，避免 PageHelper、MyBatis-Plus 场景大量误报；实体属性级结果标低置信度，两者都可在设置里关闭。

上游赋值分析（默认开）：DAL-001 / DAL-010 报出来后，再看一眼"上游到底有没有真的给过值"——调用点传了非 null 的实参 / setter 值，说明值被静默丢弃，置信度上调；找到的赋值全是字面量 `null`，说明大概率是可以直接删的死参数，置信度下调；判断不了（反射调用、扫描范围外）就不下结论，原样展示。

覆盖的 Mapper 写法：MyBatis XML、`@Select` 等注解 SQL（含 `<script>`）、iBatis 2 XML、`<include>` 跨文件展开、`parameterMap`。

## 使用

- 编辑器右键 → 检查当前文件的 Mapper 参数契约
- Project 视图右键 → 检查所选范围的 Mapper 参数契约（文件 / 目录 / Module）
- Tools → MyBatis Mapper Checker → 检查整个项目
- Analyze → Inspect Code，勾选 MyBatis Mapper 参数契约（批量模式）

结果在底部工具窗口 `MyBatis Mapper Checker` 中，按 Module → 文件分组，带置信度与备注。双击跳到 Java 位置，右键可跳 Mapper、豁免此处、忽略此处 / 参数 / statement、查看该处提交信息、查看调用链、导出 Markdown / CSV。工具栏"显示责任人"（默认关，需要项目在版本控制下且装了对应 VCS 插件如 Git4Idea）打开后，每条问题后面会带上最后修改它的作者与日期，方便按人分派。

右键"查看调用链"：从问题所在方法反向找调用者，一直找到没有调用者的方法（Controller / 定时任务 / 测试……），帮你看清一个参数最初是从哪个入口一路传下来的。按需计算，不进批量扫描；深度、每层分支数、总节点数都有上限，超限会在结果里明说，不会静默漏掉。

编辑器默认不出现任何实时波浪线。设置里打开"实时提示纯 Java 规则"后，DAL-004 / 020 / 022 / 030 这四条不依赖 Mapper 的规则会在编辑器里即时提示。

## 抑制与豁免

个人 / 项目级抑制，不留痕：

```java
@SuppressWarnings("DAL-001")
int query(@Param("a") Long a, @Param("b") Long b);

params.put("debugFlag", flag); // mapper-checker: ignore
```

团队级豁免，进版本库、报告里仍可见（"已豁免"分组）。项目根或任一 Module 根下的 `.binding-scan-ignore.yml`：

```yaml
- rule: DAL-010                         # 省略则匹配该 target 的全部规则
  target: com.example.OrderQuery#poiId  # 也可只写 statement：com.example.dao.OrderMapper.legacy
  reason: 由拦截器注入
  by: 张三
  at: 2026-09-07
```

`reason` / `by` / `at` 缺一条即无效，报告会提示。报告右键"豁免此处"会自动追加一条。

设置页（Settings → Tools → MyBatis Mapper Checker）可配置忽略参数（支持 `page*`）、忽略 statement、组合抑制、忽略路径模式（如 `**/mapper/oracle/**`）、跨方法追踪深度、严格 / 兼容可见性模式、规则启停与级别、Query 类后缀、拷贝方法源参数位置、模板 statement 列表、实时提示开关、上游赋值分析开关。配置存放在 `.idea/mybatis-mapper-checker.xml`。

## 开发

```text
JDK 17+（本机 21）    Gradle 9.6（wrapper）    编译平台 IDEA IC 2024.2.6    since-build 233（2023.3）
```

```bash
./gradlew build                       # 编译 + 全部测试
./gradlew :checker-idea:buildPlugin   # 产出 checker-idea/build/distributions/*.zip
./gradlew :checker-idea:runIde        # 沙箱 IDEA（中文界面）
./gradlew :checker-idea:verifyPlugin  # Plugin Verifier 校验 2023.3 / 2024.x
```

### CI

- 推送到 `main` 或提 PR：GitHub Actions 自动编译、跑全部测试、产出插件包，在 Actions 页面的 Artifacts 里下载 `mybatis-mapper-checker-plugin`。
- 发布版本：打 `v*` 标签并推送，例如 `git tag v0.1.0 && git push origin v0.1.0`，Release 工作流会以标签号为版本号构建、运行 Plugin Verifier，并在 Releases 页面附上 zip。

模块：

- `checker-core`：纯 Java 模型、参数名归一化、差集与规则，不依赖任何 IDEA API，JUnit 5。
- `checker-idea`：索引、XML / 注解 / Java PSI 解析、数据流追踪、模块可见性、后台任务、报告窗口、设置、导航。

方案文档：`mybatis-mapper-checker-idea-plugin-implementation-plan.md`；任务进度：`TASKS.md`。
