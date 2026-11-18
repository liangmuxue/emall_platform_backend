package com.emall.assistant;

import java.util.List;
import java.util.Map;

import com.emall.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductAssistantController {

    private final ProductAssistantService assistant;

    public ProductAssistantController(ProductAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping(value = "/api/merchant/products/assistant", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> chat(
            HttpServletRequest request,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "draft", required = false) String draft,
            @RequestParam(value = "history", required = false) String history,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        return ApiResponse.ok(assistant.chat(request, message, draft, history, files));
    }
}
