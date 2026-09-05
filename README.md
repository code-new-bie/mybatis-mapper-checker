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

| 规则 | 含义 | 默认级别 |
|---|---|---|
| MMC001 | 参数已声明或传入，但 Mapper SQL 未使用 | Warning |
| MMC002 | statement 不存在（接口方法既无 XML 也无 SQL 注解） | Error |
| MMC003 | statement 存在多个候选，无法确定目标 | Warning |

覆盖的 Java 写法：Mapper 接口 `@Param` / 无 `@Param` 别名组、实体参数逐属性比对（含 `#{q.prop}` 与 `<if test="q.prop">` 路径）、单 Map 参数到调用点的 `put` 追踪、跨方法构造的参数对象、Bean setter、`SqlSession` 与 iBatis 2 `SqlMapClient` 字符串调用。

内置分页 / 排序参数名单（pageNum、pageSize、offset、limit、orderBy 等）默认忽略，避免 PageHelper、MyBatis-Plus 场景大量误报；实体属性级结果标低置信度，两者都可在设置里关闭。

覆盖的 Mapper 写法：MyBatis XML、`@Select` 等注解 SQL（含 `<script>`）、iBatis 2 XML、`<include>` 跨文件展开、`parameterMap`。

## 使用

- 编辑器右键 → 检查当前文件的 Mapper 参数契约
- Project 视图右键 → 检查所选范围的 Mapper 参数契约（文件 / 目录 / Module）
- Tools → MyBatis Mapper Checker → 检查整个项目
- Analyze → Inspect Code，勾选 MyBatis Mapper 参数契约（批量模式）

结果在底部工具窗口 `MyBatis Mapper Checker` 中，按 Module → 文件分组，带置信度与备注。双击跳到 Java 位置，右键可跳 Mapper、忽略此处 / 参数 / statement、导出 Markdown / CSV。

编辑器中不会出现任何实时波浪线。

## 抑制

```java
@SuppressWarnings("MMC001")
int query(@Param("a") Long a, @Param("b") Long b);

params.put("debugFlag", flag); // mapper-checker: ignore
```

设置页（Settings → Tools → MyBatis Mapper Checker）可配置忽略参数（支持 `page*`）、忽略 statement、组合抑制、忽略路径模式（如 `**/mapper/oracle/**`）、跨方法追踪深度、严格 / 兼容可见性模式、规则启停与级别。配置存放在 `.idea/mybatis-mapper-checker.xml`。

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
