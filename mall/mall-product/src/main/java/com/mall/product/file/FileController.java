package com.mall.product.file;

import com.mall.common.result.R;
import com.mall.product.entity.FileRecordEntity;
import com.mall.security.util.SecurityUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件上传：{@code POST /api/file/upload}，登录即可（三端角色通用）。
 *
 * <p>返回 {@code {fileId, url}}，url 形如 {@code /static/202610/uuid.jpg}，
 * 可直接作为商品图/资质图/头像等的前端展示地址。</p>
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        FileRecordEntity record = fileService.store(file, SecurityUtils.getLoginUser());
        Map<String, Object> data = new HashMap<>();
        data.put("fileId", record.getId());
        data.put("url", record.getUrl());
        return R.ok(data);
    }
}
