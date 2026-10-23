package com.mall.order.refund;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.order.entity.RefundOrderEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 Task 1：退款单表映射与状态常量（DDL 已应用到本地 mall 库）。
 */
class RefundStatusTest {

    @Test
    void refund_entity_maps_to_refund_order_table() {
        TableName ann = RefundOrderEntity.class.getAnnotation(TableName.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("refund_order");
    }

    @Test
    void refund_status_codes_are_stable_and_distinct() {
        assertThat(RefundStatus.PENDING).isZero();
        assertThat(RefundStatus.REFUNDED).isEqualTo(2);
        assertThat(RefundStatus.REJECTED).isEqualTo(3);
        assertThat(RefundStatus.FAILED).isEqualTo(4);

        assertThat(RefundStatus.SUB_REFUNDING).isEqualTo(5);
        assertThat(RefundStatus.SUB_REFUNDED).isEqualTo(6);
        assertThat(RefundStatus.SUB_PART_REFUNDED).isEqualTo(7);
        assertThat(RefundStatus.ORDER_REFUNDED).isEqualTo(4);
    }
}
