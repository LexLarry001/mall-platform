-- M8：商品语义向量
CREATE TABLE IF NOT EXISTS product_embedding (
  spu_id       BIGINT       NOT NULL COMMENT '与 product.id 一一对应',
  model        VARCHAR(64)  NOT NULL COMMENT '如 text-embedding-v3',
  dim          INT          NOT NULL COMMENT '向量维度',
  vector       LONGTEXT     NOT NULL COMMENT 'JSON 数组，如 [0.12,-0.03,...]',
  text_hash    CHAR(64)     NOT NULL COMMENT '向量化文本的 SHA-256，判断是否需重建',
  status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待生成 1成功 2失败',
  retry_count  INT          NOT NULL DEFAULT 0,
  error_msg    VARCHAR(255) NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (spu_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '商品语义向量';
