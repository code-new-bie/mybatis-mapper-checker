package com.example.order.service;

import com.example.order.dao.OrderMapper;
import org.apache.ibatis.session.SqlSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrderService {

    private OrderMapper orderMapper;
    private SqlSession sqlSession;

    /** 调用点 put 了 poiId，XML 没用 → DAL-001，位置在 put 行。 */
    public List<Object> byMap(Long merchantId, Long poiId) {
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", merchantId);
        params.put("poiId", poiId);
        return orderMapper.queryByMap(params);
    }

    /** 跨方法构造：buildParams 里的 poiId 未用 → DAL-001（中置信度，附路径）。 */
    public List<Object> byBuilder(Long merchantId, Long poiId) {
        return sqlSession.selectList("com.example.order.dao.OrderMapper.queryByMap", buildParams(merchantId, poiId));
    }

    private Map<String, Object> buildParams(Long merchantId, Long poiId) {
        Map<String, Object> m = new HashMap<>();
        m.put("merchantId", merchantId);
        m.put("poiId", poiId);
        return m;
    }

    /** 入参直接传入：无法解析，列入报告"无法解析"分组。 */
    public List<Object> passThrough(Map<String, Object> incoming) {
        return orderMapper.queryByMap(incoming);
    }

    /** 抑制示例。 */
    public List<Object> suppressed(Long merchantId, boolean debug) {
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", merchantId);
        params.put("debug", debug); // mapper-checker: ignore
        return orderMapper.queryByMap(params);
    }
}
