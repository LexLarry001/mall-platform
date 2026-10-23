package com.mall.merchant.apply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 商家入驻申请入参。申请信息存 merchant 草稿字段，审核通过时才生成 shop 行。
 *
 * @param subjectName 主体名称（公司/个体户全称）
 * @param licenseNo   统一社会信用代码/证件号
 * @param licenseImg  证件照 URL（经 /api/file/upload 上传获得）
 * @param shopName    申请开店的店铺名称
 * @param shopLogo    店铺 Logo URL
 * @param shopIntro   店铺简介
 */
public record ApplyRequest(
        @NotBlank(message = "主体名称不能为空") @Size(max = 64, message = "主体名称过长") String subjectName,
        @NotBlank(message = "证件号不能为空") @Size(max = 64, message = "证件号过长") String licenseNo,
        @Size(max = 255, message = "证件照 URL 过长") String licenseImg,
        @NotBlank(message = "店铺名称不能为空") @Size(max = 64, message = "店铺名称过长") String shopName,
        @Size(max = 255, message = "店铺 Logo URL 过长") String shopLogo,
        @Size(max = 500, message = "店铺简介过长") String shopIntro) {
}
