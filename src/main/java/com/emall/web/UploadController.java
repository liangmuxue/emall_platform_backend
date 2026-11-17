package com.emall.web;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.emall.api.ApiException;
import com.emall.api.ApiResponse;
import com.emall.config.FileStorageConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final FileStorageConfig storage;

    public UploadController(FileStorageConfig storage) {
        this.storage = storage;
    }

    @PostMapping("/api/merchant/uploads")
    public ApiResponse<Map<String, String>> upload(HttpServletRequest request, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择图片");
        }
        String type = file.getContentType() == null ? "" : file.getContentType();
        if (!TYPES.contains(type)) {
            throw new ApiException(400, "图片格式仅支持 JPG、PNG 或 WebP");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(400, "图片不能超过 5MB，请压缩后重新上传");
        }
        String extension = switch (type) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        Files.createDirectories(storage.uploadDir());
        String name = UUID.randomUUID() + extension;
        file.transferTo(storage.uploadDir().resolve(name));
        String url = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/files/" + name;
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", url);
        return ApiResponse.ok(body);
    }
}
