package com.example.order.service;

import com.example.order.dao.OrderQuery;
import com.example.order.web.OrderRequest;
import org.springframework.beans.BeanUtils;

/** 团队规范纯 Java 规则演示（设置里开"实时提示纯 Java 规则"后，编辑器里直接可见）。 */
public class OrderConverter {

    /** DAL-020：转换方法内反射拷贝；DAL-004：源对象位置传的是刚 new 的空对象。 */
    public OrderQuery toQuery(OrderRequest req) {
        OrderQuery q = new OrderQuery();
        BeanUtils.copyProperties(q, req);
        return q;
    }

    /** DAL-030：跨层改名，merchantId → merchantIds 单复数变体；remark ← remarks 同理。 */
    public OrderQuery convert(OrderRequest req) {
        OrderQuery q = new OrderQuery();
        q.setMerchantId(req.getMerchantIds());
        q.setPoiId(req.getPoiId());
        q.setRemark(req.getRemarks());
        return q;
    }
}
