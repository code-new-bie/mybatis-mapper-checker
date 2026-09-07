package com.example.order.web;

/** Web 层入参，字段名与 OrderQuery 故意不一致，配合 OrderConverter 演示 DAL-030。 */
public class OrderRequest {
    private Long merchantIds;
    private Long poiId;
    private String remarks;

    public Long getMerchantIds() { return merchantIds; }
    public void setMerchantIds(Long merchantIds) { this.merchantIds = merchantIds; }
    public Long getPoiId() { return poiId; }
    public void setPoiId(Long poiId) { this.poiId = poiId; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
}
