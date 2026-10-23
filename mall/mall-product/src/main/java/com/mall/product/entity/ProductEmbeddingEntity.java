package com.mall.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 商品语义向量（与 product 一一对应）。 */
@TableName("product_embedding")
public class ProductEmbeddingEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_OK     = 1;
    public static final int STATUS_FAIL   = 2;

    @TableId(type = IdType.INPUT)
    private Long spuId;
    private String model;
    private Integer dim;
    /** JSON 数组，如 [0.12,-0.03,...] */
    private String vector;
    private String textHash;
    private Integer status;
    private Integer retryCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getDim() { return dim; }
    public void setDim(Integer dim) { this.dim = dim; }
    public String getVector() { return vector; }
    public void setVector(String vector) { this.vector = vector; }
    public String getTextHash() { return textHash; }
    public void setTextHash(String textHash) { this.textHash = textHash; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
