package com.mall.order.address;

import com.mall.common.result.R;
import com.mall.order.address.dto.AddressGeocodeView;
import com.mall.order.address.dto.AddressSaveRequest;
import com.mall.order.address.dto.AddressView;
import com.mall.order.logistics.AmapClient;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家收货地址接口（ROLE_USER，由过滤链保证）。
 *
 * <p>userId 一律取自登录态（{@link SecurityUtils#getAccountId()}），禁止前端传参。</p>
 */
@RestController
@RequestMapping("/api/user/address")
public class AddressController {

    private final AddressService addressService;
    private final AmapClient amapClient;

    public AddressController(AddressService addressService, AmapClient amapClient) {
        this.addressService = addressService;
        this.amapClient = amapClient;
    }

    /** 我的地址列表（默认在前）。 */
    @GetMapping
    public R<List<AddressView>> list() {
        return R.ok(addressService.list(SecurityUtils.getAccountId()));
    }

    /**
     * 校验地址能否被高德识别（只读，不落库）。
     *
     * <p>未配置 Key、地址不完整或高德无结果时统一返回 recognized=false，
     * 前端据此提示用户补全省市区与门牌号。</p>
     */
    @GetMapping("/geocode")
    public R<AddressGeocodeView> geocode(@RequestParam("address") String address) {
        return amapClient.geocodeDetail(address)
                .map(r -> R.ok(new AddressGeocodeView(true, r.lng(), r.lat(),
                        r.formattedAddress(), r.level(), "已识别")))
                .orElseGet(() -> R.ok(new AddressGeocodeView(false, null, null, null, null,
                        "未识别：请补全省市区与详细地址，或确认后端已配置高德 Web 服务 Key")));
    }

    /** 新增地址。 */
    @PostMapping
    public R<Long> create(@Valid @RequestBody AddressSaveRequest request) {
        return R.ok(addressService.create(SecurityUtils.getAccountId(), request));
    }

    /** 编辑地址。 */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AddressSaveRequest request) {
        addressService.update(SecurityUtils.getAccountId(), id, request);
        return R.ok();
    }

    /** 删除地址。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        addressService.delete(SecurityUtils.getAccountId(), id);
        return R.ok();
    }

    /** 设为默认地址。 */
    @PostMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(SecurityUtils.getAccountId(), id);
        return R.ok();
    }
}
