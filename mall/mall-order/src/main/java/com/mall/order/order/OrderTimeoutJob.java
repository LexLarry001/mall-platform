package com.mall.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 超时关单定时任务（plan Task 4）：每分钟扫描 {@code mall:order:timeout} ZSet，
 * ZRANGEBYSCORE (0, now] 取出支付截止已到期的主订单号，按取消语义关单（幂等：仅 0→3 生效）。
 *
 * <p>单个订单关单失败仅记日志，不阻塞其余订单；残留锚点下一分钟重扫重试。</p>
 */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final StringRedisTemplate redis;
    private final OrderService orderService;

    public OrderTimeoutJob(StringRedisTemplate redis, OrderService orderService) {
        this.redis = redis;
        this.orderService = orderService;
    }

    /** 每分钟扫描一次，到期关单。 */
    @Scheduled(fixedDelay = 60_000)
    public void closeExpired() {
        Set<String> orderNos = redis.opsForZSet()
                .rangeByScore(OrderService.TIMEOUT_KEY, 0, System.currentTimeMillis());
        if (orderNos == null || orderNos.isEmpty()) {
            return;
        }
        for (String orderNo : orderNos) {
            try {
                orderService.cancelTimeout(orderNo);
            } catch (Exception e) {
                log.warn("超时关单失败，下一分钟重试: orderNo={}", orderNo, e);
            }
        }
    }
}
