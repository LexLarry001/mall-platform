package com.mall.product.mongo;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核留痕写入与查询（MongoDB {@code merchant_audit_log}）。
 *
 * <p>留痕属"尽力而为"的审计数据，不参与业务事务；入驻申请与商品审核两处复用本服务。</p>
 */
@Service
public class AuditLogService {

    private final MongoTemplate mongoTemplate;

    public AuditLogService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** 追加一条审核留痕。 */
    public void record(String targetType, Long targetId, String action, String operator, String remark) {
        MerchantAuditLogDoc doc = new MerchantAuditLogDoc();
        doc.setTargetType(targetType);
        doc.setTargetId(targetId);
        doc.setAction(action);
        doc.setOperator(operator);
        doc.setRemark(remark);
        doc.setCreateTime(LocalDateTime.now());
        mongoTemplate.insert(doc);
    }

    /** 按审核对象查全部留痕（按时间升序）。 */
    public List<MerchantAuditLogDoc> listByTarget(String targetType, Long targetId) {
        Query query = Query.query(Criteria.where("targetType").is(targetType).and("targetId").is(targetId))
                .with(Sort.by(Sort.Direction.ASC, "createTime"));
        return mongoTemplate.find(query, MerchantAuditLogDoc.class);
    }
}
