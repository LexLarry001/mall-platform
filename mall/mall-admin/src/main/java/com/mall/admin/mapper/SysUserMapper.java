package com.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.admin.entity.SysUserEntity;
import org.apache.ibatis.annotations.Mapper;

/** 平台管理员账号 Mapper。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUserEntity> {

    /** 按用户名查询未删除的管理员账号，不存在返回 {@code null}。 */
    default SysUserEntity selectByUsername(String username) {
        return selectOne(Wrappers.<SysUserEntity>lambdaQuery().eq(SysUserEntity::getUsername, username));
    }
}
