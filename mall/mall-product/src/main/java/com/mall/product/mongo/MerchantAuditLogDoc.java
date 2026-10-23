package com.mall.product.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * 审核留痕（MongoDB 集合 {@code merchant_audit_log}）。
 *
 * <p>入驻申请与商品审核共用（规格 §4：审核留痕属"结构随业务扩展"的文档式数据），
 * 按 {@code target_type + target_id} 回溯某次申请/商品的全部审核动作。</p>
 */
@Document("merchant_audit_log")
public class MerchantAuditLogDoc {

    /** 审核对象：入驻申请。 */
    public static final String TARGET_MERCHANT_APPLY = "MERCHANT_APPLY";
    /** 审核对象：商品（Task 7 商品审核复用）。 */
    public static final String TARGET_PRODUCT = "PRODUCT";

    /** 动作：提交。 */
    public static final String ACTION_SUBMIT = "SUBMIT";
    /** 动作：通过。 */
    public static final String ACTION_PASS = "PASS";
    /** 动作：驳回。 */
    public static final String ACTION_REJECT = "REJECT";
    /** 动作：平台违规下架（仅商品）。 */
    public static final String ACTION_VIOLATION_OFF = "VIOLATION_OFF";

    @Id
    private String id;

    /** 审核对象类型：MERCHANT_APPLY / PRODUCT。 */
    private String targetType;

    /** 审核对象 ID（merchant.id / product.id）。 */
    @Indexed
    private Long targetId;

    /** 动作：SUBMIT / PASS / REJECT。 */
    private String action;

    /** 操作者标识，如 "MERCHANT:1001" / "ADMIN:1"。 */
    private String operator;

    /** 备注（驳回原因等）。 */
    private String remark;

    private LocalDateTime createTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
