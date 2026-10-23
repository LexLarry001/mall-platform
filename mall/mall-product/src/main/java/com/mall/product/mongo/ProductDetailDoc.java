package com.mall.product.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品富文本详情（MongoDB 集合 {@code product_detail}）。
 *
 * <p>富文本 HTML 与图集结构不固定、随编辑整篇替换，典型文档式数据（规格 §4/§6.2）；
 * {@code spu_id} 唯一索引，与 MySQL {@code product} 一一对应。</p>
 */
@Document("product_detail")
public class ProductDetailDoc {

    @Id
    private String id;

    /** 对应 MySQL product.id，唯一。 */
    @Indexed(unique = true)
    private Long spuId;

    /** 富文本 HTML（wangEditor 产出）。 */
    private String html;

    /** 详情图集 URL 列表。 */
    private List<String> gallery;

    private LocalDateTime updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getHtml() {
        return html;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public List<String> getGallery() {
        return gallery;
    }

    public void setGallery(List<String> gallery) {
        this.gallery = gallery;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
