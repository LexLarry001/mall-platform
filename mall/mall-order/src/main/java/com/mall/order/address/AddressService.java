package com.mall.order.address;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.address.dto.AddressSaveRequest;
import com.mall.order.address.dto.AddressView;
import com.mall.order.entity.UserAddressEntity;
import com.mall.order.mapper.UserAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务。
 *
 * <p>不变量：同一买家至多一条默认地址——凡写入默认（新增/编辑/设默认）都先在同事务内
 * 清掉旧默认再置新默认；删除默认地址后<strong>不</strong>提拔新默认（plan Task 1 原文语义）。</p>
 *
 * <p>越权与不存在统一抛"地址不存在"，不泄露地址存在性。</p>
 */
@Service
public class AddressService {

    private final UserAddressMapper addressMapper;

    public AddressService(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    /** 我的地址列表：默认在前，其余按 ID 倒序（新在前）。 */
    public List<AddressView> list(Long userId) {
        return addressMapper.selectList(Wrappers.<UserAddressEntity>lambdaQuery()
                        .eq(UserAddressEntity::getUserId, userId)
                        .orderByDesc(UserAddressEntity::getIsDefault)
                        .orderByDesc(UserAddressEntity::getId))
                .stream().map(this::toView).toList();
    }

    /** 新增地址；isDefault=true 时同事务清旧默认。 */
    @Transactional
    public Long create(Long userId, AddressSaveRequest request) {
        UserAddressEntity entity = new UserAddressEntity();
        fill(entity, request);
        entity.setUserId(userId);
        entity.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        if (entity.getIsDefault() == 1) {
            clearDefault(userId);
        }
        addressMapper.insert(entity);
        return entity.getId();
    }

    /** 编辑地址（PUT 全量替换语义，默认标记以请求为准）；越权/不存在一律"地址不存在"。 */
    @Transactional
    public void update(Long userId, Long id, AddressSaveRequest request) {
        UserAddressEntity entity = requireOwned(userId, id);
        fill(entity, request);
        if (Boolean.TRUE.equals(request.isDefault())) {
            clearDefault(userId);
            entity.setIsDefault(1);
        } else {
            entity.setIsDefault(0);
        }
        addressMapper.updateById(entity);
    }

    /** 删除地址（物理删除）；删除默认地址后不提拔新默认。 */
    public void delete(Long userId, Long id) {
        UserAddressEntity entity = requireOwned(userId, id);
        addressMapper.deleteById(entity.getId());
    }

    /** 设默认：同事务先清旧默认再置目标。 */
    @Transactional
    public void setDefault(Long userId, Long id) {
        UserAddressEntity entity = requireOwned(userId, id);
        clearDefault(userId);
        entity.setIsDefault(1);
        addressMapper.updateById(entity);
    }

    private UserAddressEntity requireOwned(Long userId, Long id) {
        UserAddressEntity entity = addressMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BizException("地址不存在");
        }
        return entity;
    }

    private void clearDefault(Long userId) {
        addressMapper.update(null, Wrappers.<UserAddressEntity>lambdaUpdate()
                .eq(UserAddressEntity::getUserId, userId)
                .eq(UserAddressEntity::getIsDefault, 1)
                .set(UserAddressEntity::getIsDefault, 0));
    }

    private void fill(UserAddressEntity entity, AddressSaveRequest request) {
        entity.setReceiver(request.receiver());
        entity.setPhone(request.phone());
        entity.setProvince(request.province());
        entity.setCity(request.city());
        entity.setDistrict(request.district());
        entity.setDetail(request.detail());
    }

    private AddressView toView(UserAddressEntity entity) {
        return new AddressView(entity.getId(), entity.getReceiver(), entity.getPhone(),
                entity.getProvince(), entity.getCity(), entity.getDistrict(),
                entity.getDetail(), entity.getIsDefault());
    }
}
