# 演示项目

用 `./gradlew :checker-idea:runIde` 启动沙箱 IDEA，打开本目录（Maven 项目），等索引完成后：

Tools → MyBatis Mapper Checker → 检查整个项目

预期报告：

| 规则 | 参数 | statement | 位置 |
|---|---|---|---|
| MMC001 | poiId | OrderMapper.queryOrder | OrderMapper.java（@Param 参数） |
| MMC001 | merchantId | OrderMapper.queryByPositional | OrderMapper.java，中置信度 |
| MMC001 | shopId | OrderMapper.queryByAnnotation | OrderMapper.java |
| MMC001 | poiId | OrderMapper.queryByMap | OrderService.java byMap 的 put 行 |
| MMC001 | poiId | OrderMapper.queryByMap | OrderService.java buildParams 的 put 行，中置信度，附路径 |
| MMC001 | remark | OrderMapper.queryByBean | OrderQuery.java 字段，低置信度（pageNum / pageSize 被分页名单忽略） |
| MMC002 | — | OrderMapper.missingStatement | OrderMapper.java 方法名 |

无法解析分组：passThrough 的 incoming（参数对象是方法入参）。
已抑制：suppressed 中的 debug。
