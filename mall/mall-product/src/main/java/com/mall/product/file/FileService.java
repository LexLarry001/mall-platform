package com.mall.product.file;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.entity.FileRecordEntity;
import com.mall.product.mapper.FileRecordMapper;
import com.mall.security.token.LoginUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本地磁盘文件存储：图片白名单 + 魔数二次校验，存 {@code uploads/yyyyMM/UUID.扩展名}，
 * 经 {@code /static/**} 静态映射对外访问，元数据落 {@code file_record} 表。
 *
 * <p>安全要点（plan Review Focus 6）：contentType 与后缀均来自客户端、不可信，
 * 因此以"白名单 contentType + 文件头魔数"双重校验；存储路径只使用服务端生成的 UUID 名，
 * 原始文件名仅展示用且剥掉目录部分，杜绝路径穿越。</p>
 */
@Service
public class FileService {

    /** 单文件上限 5MB（与 spring.servlet.multipart.max-file-size 对齐）。 */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** contentType → 扩展名（白名单）。 */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    /** 魔数读取长度（WEBP 需要前 12 字节）。 */
    private static final int MAGIC_LENGTH = 12;

    private final FileRecordMapper fileRecordMapper;
    private final Path uploadDir;

    public FileService(FileRecordMapper fileRecordMapper,
                       @Value("${mall.file.upload-dir:uploads}") String uploadDir) {
        this.fileRecordMapper = fileRecordMapper;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 校验并存储上传文件。
     *
     * @param file     multipart 文件
     * @param uploader 当前登录态（上传者类型/ID 取自此，禁止前端传参）
     * @return 已插入的 file_record 实体（含对外 url）
     * @throws BizException 400：空文件 / 超 5MB / 非图片类型 / 内容与类型不符
     */
    public FileRecordEntity store(MultipartFile file, LoginUser uploader) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "文件大小不能超过 5MB");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = ALLOWED_TYPES.get(contentType);
        if (extension == null) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "仅支持 jpg/png/webp 图片");
        }
        if (!matchesMagic(file, contentType)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "文件内容与图片类型不符");
        }

        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String storedName = UUID.randomUUID() + "." + extension;
        Path target = uploadDir.resolve(month).resolve(storedName).normalize();
        if (!target.startsWith(uploadDir)) {
            // 防御性兜底：存储名本为服务端生成，正常不会触发
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "非法文件名");
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new BizException("文件存储失败，请重试");
        }

        FileRecordEntity record = new FileRecordEntity();
        record.setOriginalName(stripPath(file.getOriginalFilename()));
        record.setStoredName(storedName);
        record.setUrl("/static/" + month + "/" + storedName);
        record.setSize(file.getSize());
        record.setContentType(contentType);
        record.setUploaderType(uploader.type().name());
        record.setUploaderId(uploader.accountId());
        fileRecordMapper.insert(record);
        return record;
    }

    /** 文件头魔数校验：JPEG=FF D8 FF；PNG=89 50 4E 47 0D 0A 1A 0A；WEBP=RIFF????WEBP。 */
    private boolean matchesMagic(MultipartFile file, String contentType) {
        byte[] head = new byte[MAGIC_LENGTH];
        int len;
        try (InputStream in = file.getInputStream()) {
            len = in.read(head);
        } catch (IOException e) {
            return false;
        }
        return switch (contentType) {
            case "image/jpeg" -> len >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/png" -> len >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47
                    && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
            case "image/webp" -> len >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    /** 剥掉原始文件名中的目录部分（仅展示用，不参与存储路径）。 */
    private String stripPath(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed";
        }
        String normalized = originalFilename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
