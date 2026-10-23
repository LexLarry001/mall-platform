package com.mall.order.address;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.exception.BizException;
import com.mall.order.address.dto.AddressSaveRequest;
import com.mall.order.address.dto.AddressView;
import com.mall.order.entity.UserAddressEntity;
import com.mall.order.mapper.UserAddressMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收货地址服务单元测试（mapper mock）。
 *
 * <p>钉死 plan Task 1 语义：设默认同事务清旧默认（先清后写）；越权/不存在一律"地址不存在"；
 * 删除默认地址后不落新默认。默认排序（默认在前）由 HTTP 链路测试钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 202L;

    @Mock
    private UserAddressMapper addressMapper;

    private AddressService addressService;

    /**
     * 纯 mock 单测无 SqlSessionFactory，MyBatis-Plus lambda 包装器立即解析列时
     * （如 lambdaUpdate().set()）需要实体 TableInfo 缓存，此处手动初始化。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserAddressEntity.class);
    }

    @BeforeEach
    void setUp() {
        addressService = new AddressService(addressMapper);
    }

    /** 新增默认地址：先清旧默认再插入，插入行 is_default=1。 */
    @Test
    void create_default_clears_old_default_before_insert() {
        AddressSaveRequest request = new AddressSaveRequest(
                "张三", "13800000001", "浙江省", "杭州市", "西湖区", "文三路 100 号", true);

        addressService.create(USER_ID, request);

        InOrder inOrder = inOrder(addressMapper);
        ArgumentCaptor<UserAddressEntity> captor = ArgumentCaptor.forClass(UserAddressEntity.class);
        inOrder.verify(addressMapper).update(isNull(), any());
        inOrder.verify(addressMapper).insert(captor.capture());
        UserAddressEntity inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getIsDefault()).isEqualTo(1);
        assertThat(inserted.getReceiver()).isEqualTo("张三");
    }

    /** 新增非默认地址：不清旧默认，插入行 is_default=0。 */
    @Test
    void create_non_default_does_not_touch_old_default() {
        AddressSaveRequest request = new AddressSaveRequest(
                "张三", "13800000001", "浙江省", "杭州市", "西湖区", "文三路 100 号", false);

        addressService.create(USER_ID, request);

        verify(addressMapper, never()).update(isNull(), any());
        ArgumentCaptor<UserAddressEntity> captor = ArgumentCaptor.forClass(UserAddressEntity.class);
        verify(addressMapper).insert(captor.capture());
        assertThat(captor.getValue().getIsDefault()).isEqualTo(0);
    }

    /** 设默认：先清旧默认再把目标置 1。 */
    @Test
    void set_default_clears_old_then_marks_target() {
        UserAddressEntity target = ownedAddress(9001L, 0);
        when(addressMapper.selectById(9001L)).thenReturn(target);

        addressService.setDefault(USER_ID, 9001L);

        InOrder inOrder = inOrder(addressMapper);
        inOrder.verify(addressMapper).update(isNull(), any());
        inOrder.verify(addressMapper).updateById(target);
        assertThat(target.getIsDefault()).isEqualTo(1);
    }

    /** 越权设默认：他人地址一律"地址不存在"（不泄露存在性）。 */
    @Test
    void set_default_rejects_foreign_address() {
        UserAddressEntity foreign = ownedAddress(9001L, 0);
        foreign.setUserId(OTHER_USER_ID);
        when(addressMapper.selectById(9001L)).thenReturn(foreign);

        assertThatThrownBy(() -> addressService.setDefault(USER_ID, 9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
    }

    /** 编辑他人地址：拒绝且不写库。 */
    @Test
    void update_rejects_foreign_address() {
        UserAddressEntity foreign = ownedAddress(9001L, 0);
        foreign.setUserId(OTHER_USER_ID);
        when(addressMapper.selectById(9001L)).thenReturn(foreign);

        AddressSaveRequest request = new AddressSaveRequest(
                "改名", "13800000001", "浙江省", "杭州市", "西湖区", "文三路 100 号", false);
        assertThatThrownBy(() -> addressService.update(USER_ID, 9001L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
        verify(addressMapper, never()).updateById(any(UserAddressEntity.class));
    }

    /** 编辑不存在的地址：拒绝。 */
    @Test
    void update_rejects_missing_address() {
        when(addressMapper.selectById(9999L)).thenReturn(null);

        AddressSaveRequest request = new AddressSaveRequest(
                "改名", "13800000001", "浙江省", "杭州市", "西湖区", "文三路 100 号", false);
        assertThatThrownBy(() -> addressService.update(USER_ID, 9999L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
    }

    /** 删除默认地址：仅物理删除目标行，不提拔任何新默认（plan 原文语义）。 */
    @Test
    void delete_default_does_not_promote_another() {
        UserAddressEntity defaultAddress = ownedAddress(9001L, 1);
        when(addressMapper.selectById(9001L)).thenReturn(defaultAddress);

        addressService.delete(USER_ID, 9001L);

        verify(addressMapper).deleteById(9001L);
        verify(addressMapper, never()).update(isNull(), any());
        verify(addressMapper, never()).updateById(any(UserAddressEntity.class));
    }

    /** 删除他人地址：拒绝且不删库。 */
    @Test
    void delete_rejects_foreign_address() {
        UserAddressEntity foreign = ownedAddress(9001L, 0);
        foreign.setUserId(OTHER_USER_ID);
        when(addressMapper.selectById(9001L)).thenReturn(foreign);

        assertThatThrownBy(() -> addressService.delete(USER_ID, 9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("地址不存在");
        verify(addressMapper, never()).deleteById(any(Long.class));
    }

    /** 列表：实体正确映射为视图。 */
    @Test
    void list_maps_entities_to_views() {
        when(addressMapper.selectList(any())).thenReturn(List.of(ownedAddress(9001L, 1)));

        List<AddressView> views = addressService.list(USER_ID);

        assertThat(views).hasSize(1);
        AddressView view = views.get(0);
        assertThat(view.id()).isEqualTo(9001L);
        assertThat(view.receiver()).isEqualTo("张三");
        assertThat(view.isDefault()).isEqualTo(1);
    }

    private UserAddressEntity ownedAddress(Long id, int isDefault) {
        UserAddressEntity entity = new UserAddressEntity();
        entity.setId(id);
        entity.setUserId(USER_ID);
        entity.setReceiver("张三");
        entity.setPhone("13800000001");
        entity.setProvince("浙江省");
        entity.setCity("杭州市");
        entity.setDistrict("西湖区");
        entity.setDetail("文三路 100 号");
        entity.setIsDefault(isDefault);
        return entity;
    }
}
