package com.mall.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SPU（表 {@code product}）。
 *
 * <p>status 状态机：0 草稿 / 1 待审核 / 2 已上架 / 3 审核驳回 / 4 商家下架 / 5 平台违规下架。
 * 仅 status=2 对买家可见。</p>
 */
@TableName("product")
public class ProductEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属店铺（数据级鉴权依据，来自登录态而非前端传参）。 */
    private Long shopId;

    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainImg;

    /** 图集，逗号分隔 URL。 */
    private String imgList;

    /** 展示价 = SKU 最低价（冗余维护，列表页免聚合）。 */
    private BigDecimal price;

    /** 销量（M3 订单域维护）。 */
    private Integer sales;

    /** 评分汇总（M5 评价域维护），默认 5.0。 */
    private BigDecimal avgRating;

    private Integer commentCount;

    /** 评价版本号（M10）：新增/隐藏评价时 +1，用于 AI 摘要缓存失效。 */
    private Integer reviewVersion;

    /** 0 草稿 / 1 待审核 / 2 已上架 / 3 审核驳回 / 4 商家下架 / 5 平台违规下架。 */
    private Integer status;

    /** 审核备注（驳回原因 / 违规下架原因）。 */
    private String auditRemark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubTitle() {
        return subTitle;
    }

    public void setSubTitle(String subTitle) {
        this.subTitle = subTitle;
    }

    public String getMainImg() {
        return mainImg;
    }

    public void setMainImg(String mainImg) {
        this.mainImg = mainImg;
    }

    public String getImgList() {
        return imgList;
    }

    public void setImgList(String imgList) {
        this.imgList = imgList;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getSales() {
        return sales;
    }

    public void setSales(Integer sales) {
        this.sales = sales;
    }

    public BigDecimal getAvgRating() {
        return avgRating;
    }

    public void setAvgRating(BigDecimal avgRating) {
        this.avgRating = avgRating;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public Integer getReviewVersion() {
        return reviewVersion;
    }

    public void setReviewVersion(Integer reviewVersion) {
        this.reviewVersion = reviewVersion;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getAuditRemark() {
        return auditRemark;
    }

    public void setAuditRemark(String auditRemark) {
        this.auditRemark = auditRemark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
