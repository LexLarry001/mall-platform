package com.mall.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mongo.ProductDetailDoc;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * mall-product 模块冒烟：六张新表 Mapper 可注入、本机 MySQL 种子类目存在、
 * MongoDB {@code product_detail.spu_id} 唯一索引生效（规格 §6.2）。
 *
 * <p>连接信息显式写在 properties 中，使本模块测试不依赖 gateway 的 application.yml。</p>
 */
@SpringBootTest(
        classes = ProductModuleSmokeTest.TestApp.class,
        properties = {
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "spring.data.mongodb.uri=mongodb://localhost:27017/mall",
                "spring.data.mongodb.auto-index-creation=true"
        })
class ProductModuleSmokeTest {

    @SpringBootApplication(scanBasePackages = {"com.mall.product", "com.mall.ai"})
    @MapperScan("com.mall.product.mapper")
    static class TestApp {
    }

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void context_should_wire_product_mappers_and_mongo_template() {
        assertThat(categoryMapper).isNotNull();
        assertThat(mongoTemplate).isNotNull();
    }

    @Test
    void local_mysql_should_contain_seed_category_tree() {
        Long count = categoryMapper.selectCount(null);
        assertThat(count).isGreaterThanOrEqualTo(9L);
    }

    @Test
    void product_detail_spu_id_should_be_unique_in_mongo() {
        ProductDetailDoc first = new ProductDetailDoc();
        first.setSpuId(990001L);
        first.setHtml("<p>a</p>");
        mongoTemplate.save(first);

        ProductDetailDoc dup = new ProductDetailDoc();
        dup.setSpuId(990001L);
        dup.setHtml("<p>b</p>");
        assertThatThrownBy(() -> mongoTemplate.save(dup))
                .isInstanceOf(DuplicateKeyException.class);

        mongoTemplate.remove(first);
    }
}
