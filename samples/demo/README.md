# 演示项目

用 `./gradlew :checker-idea:runIde` 启动沙箱 IDEA，打开本目录（Maven 项目），等索引完成后：

Tools → MyBatis Mapper Checker → 检查整个项目

预期报告：

| 规则 | 参数 | statement | 位置 |
|---|---|---|---|
| DAL-001 | poiId | OrderMapper.queryOrder | OrderMapper.java（@Param 参数）；豁免文件里这条缺 by / at，不生效，统计条提示"1 条豁免记录未生效" |
| DAL-001 | merchantId | OrderMapper.queryByPositional | OrderMapper.java，中置信度 |
| DAL-001 | poiId | OrderMapper.queryByMap | OrderService.java byMap 的 put 行 |
| DAL-001 | poiId | OrderMapper.queryByMap | OrderService.java buildParams 的 put 行，中置信度，附路径 |
| DAL-001 | remark | OrderMapper.queryByBean | OrderQuery.java 字段，低置信度（pageNum / pageSize 被分页名单忽略） |
| DAL-005 | — | OrderMapper.missingStatement | OrderMapper.java 方法名 |
| DAL-022 | query | OrderMapper.queryByBean | OrderMapper.java：Query 参数没有 @Param("query") |
| DAL-004 | q | — | OrderConverter.toQuery：copyProperties 源位置传了空对象 |
| DAL-020 | BeanUtils.copyProperties() | — | OrderConverter.toQuery：转换方法内反射拷贝 |
| DAL-030 | setMerchantId ← getMerchantIds / setRemark ← getRemarks | — | OrderConverter.convert：跨层单复数改名 |
| DAL-010 / 011 | OrderQuery 各属性 | — | 整项目范围才跑：poiId 有 setter 调用但 queryByBean 引用了它，不报；remark 有 set 无引用 → DAL-010 |

已豁免分组：queryByAnnotation 的 shopId（`.binding-scan-ignore.yml` 第一条，by demo / at 2026-09-07）。
无法解析分组：passThrough 的 incoming（参数对象是方法入参）；toQuery 里 q 被反射拷贝（REFLECTIVE_COPY）。
已抑制：suppressed 中的 debug。

设置 → Tools → MyBatis Mapper Checker 勾上"在编辑器里实时提示纯 Java 规则"后，打开 OrderConverter.java 直接能看到 DAL-004 / 020 / 030 波浪线。
