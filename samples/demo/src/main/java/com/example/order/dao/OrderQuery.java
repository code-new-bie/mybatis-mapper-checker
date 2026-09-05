package com.example.order.dao;

public class OrderQuery {
    private Integer pageNum;
    private Integer pageSize;
    private Long merchantId;
    private Long poiId;
    private String remark;

    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer pageNum) { this.pageNum = pageNum; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getPoiId() { return poiId; }
    public void setPoiId(Long poiId) { this.poiId = poiId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
