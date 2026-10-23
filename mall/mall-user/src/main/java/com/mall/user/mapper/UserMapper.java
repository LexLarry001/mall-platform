package com.mall.user.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 买家账号 Mapper。
 *
 * <p>{@code username} 唯一，认证流程按用户名精确查询。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /** 按用户名查询未删除的账号，不存在返回 {@code null}。 */
    default UserEntity selectByUsername(String username) {
        return selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));
    }
}
