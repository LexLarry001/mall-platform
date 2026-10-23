package com.mall.product.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mall.common.exception.BizException;
import com.mall.product.entity.FileRecordEntity;
import com.mall.product.mapper.FileRecordMapper;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件存储服务单元测试（磁盘落在 {@link TempDir}，mapper mock，不依赖 Spring 上下文）。
 *
 * <p>钉死 plan Review Focus 6（上传安全）：</p>
 * <ul>
 *   <li>非图片 contentType → 400</li>
 *   <li>伪造图片后缀（contentType 宣称图片但魔数不符）→ 400</li>
 *   <li>超过 5MB → 400</li>
 *   <li>原始名含 {@code ../} 路径穿越 → 存储名净化为 UUID，url 不携带原始路径</li>
 *   <li>合法 PNG → 落盘 + 写 file_record，返回 {@code /static/yyyyMM/uuid.png}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    /** 合法 PNG 魔数 + 最小尾部字节。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    private static final long MAX_SIZE = 5L * 1024 * 1024;

    @TempDir
    Path tempDir;

    @Mock
    private FileRecordMapper fileRecordMapper;

    private FileService fileService;

    private final LoginUser uploader = new LoginUser(7L, AccountType.MERCHANT, 99L, null);

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRecordMapper, tempDir.toString());
    }

    /** RF6：contentType 非图片白名单 → 400，不落盘不写库。 */
    @Test
    void non_image_content_type_should_reject_with_400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "text/plain", PNG_BYTES);

        BizException ex = catchThrowableOfType(() -> fileService.store(file, uploader), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(400);
        verifyNoInteractions(fileRecordMapper);
    }

    /** RF6：后缀/contentType 伪装成 JPEG 但内容魔数不符 → 400。 */
    @Test
    void forged_jpeg_bytes_should_reject_with_400() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                "this is not a real jpeg".getBytes());

        BizException ex = catchThrowableOfType(() -> fileService.store(file, uploader), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(400);
        verifyNoInteractions(fileRecordMapper);
    }

    /** RF6：超过 5MB（即便魔数合法）→ 400。 */
    @Test
    void oversize_file_should_reject_with_400() {
        byte[] big = new byte[(int) MAX_SIZE + 1];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        BizException ex = catchThrowableOfType(() -> fileService.store(file, uploader), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(400);
        verifyNoInteractions(fileRecordMapper);
    }

    /** RF6：原始名带路径穿越 → 存储名/uuid 净化，url 不含 {@code ..} 与原始目录。 */
    @Test
    void path_traversal_name_should_be_sanitized() {
        MockMultipartFile file = new MockMultipartFile("file", "../../../evil.png", "image/png", PNG_BYTES);

        FileRecordEntity record = fileService.store(file, uploader);

        assertThat(record.getUrl()).matches("^/static/\\d{6}/[0-9a-f-]{36}\\.png$");
        assertThat(record.getUrl()).doesNotContain("..");
        assertThat(record.getStoredName()).doesNotContain("..", "/", "\\");
        // 原始名仅作展示，亦应剥掉目录部分
        assertThat(record.getOriginalName()).isEqualTo("evil.png");
    }

    /** 合法 PNG：落盘到 yyyyMM 子目录、写 file_record、返回可访问 url。 */
    @Test
    void valid_png_should_store_file_and_insert_record() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES);

        FileRecordEntity record = fileService.store(file, uploader);

        ArgumentCaptor<FileRecordEntity> captor = ArgumentCaptor.forClass(FileRecordEntity.class);
        verify(fileRecordMapper).insert(captor.capture());
        FileRecordEntity inserted = captor.getValue();
        assertThat(inserted.getUrl()).isEqualTo(record.getUrl());
        assertThat(inserted.getSize()).isEqualTo((long) PNG_BYTES.length);
        assertThat(inserted.getContentType()).isEqualTo("image/png");
        assertThat(inserted.getUploaderType()).isEqualTo("MERCHANT");
        assertThat(inserted.getUploaderId()).isEqualTo(7L);

        // 磁盘真实落盘：url 的相对部分（/static/ 之后）即 uploads 内的相对路径
        String relative = record.getUrl().substring("/static/".length());
        Path stored = tempDir.resolve(relative);
        assertThat(stored).exists();
        try {
            assertThat(Files.readAllBytes(stored)).isEqualTo(PNG_BYTES);
        } catch (Exception e) {
            throw new AssertionError("读取落盘文件失败", e);
        }
    }

    /** WEBP 魔数（RIFF....WEBP）也应通过二次校验。 */
    @Test
    void valid_webp_should_be_accepted() {
        byte[] webp = "RIFFXXXXWEBPpayload".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "pic.webp", "image/webp", webp);

        FileRecordEntity record = fileService.store(file, uploader);

        assertThat(record.getUrl()).endsWith(".webp");
    }
}
