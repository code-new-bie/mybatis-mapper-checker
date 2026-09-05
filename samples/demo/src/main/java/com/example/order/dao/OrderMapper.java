package com.example.order.dao;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 演示：每个方法对应一种检查场景。 */
public interface OrderMapper {

    /** MMC001：poiId 声明了但 XML 没用。 */
    List<Object> queryOrder(@Param("merchantId") Long merchantId,
                            @Param("poiId") Long poiId,
                            @Param("status") String status);

    /** 无 @Param 双参数：XML 用 #{param2} 覆盖了 status，merchantId 未用 → MMC001（中置信度）。 */
    List<Object> queryByPositional(Long merchantId, String status);

    /** 单 Map：到调用点看 put 了什么。 */
    List<Object> queryByMap(Map<String, Object> params);

    /** MMC002：既无 XML 也无注解。 */
    int missingStatement(@Param("id") Long id);

    /** 注解 SQL：shopId 未用 → MMC001。 */
    @Select("SELECT * FROM orders WHERE merchant_id = #{merchantId}")
    List<Object> queryByAnnotation(@Param("merchantId") Long merchantId, @Param("shopId") Long shopId);

    /** 全部使用，无问题。 */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 单 Bean：展开属性。pageNum / pageSize 被内置分页名单忽略，remark 未用 → MMC001（低置信度，定位到 OrderQuery 字段）。 */
    List<Object> queryByBean(OrderQuery query);
}
