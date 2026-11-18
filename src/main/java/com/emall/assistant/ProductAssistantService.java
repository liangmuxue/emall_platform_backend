package com.emall.assistant;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.emall.api.ApiException;
import com.emall.config.FileStorageConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ProductAssistantService.class);
    private static final int MAX_FILES = 4;
    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final Set<String> UNITS = Set.of("份", "斤", "盒", "袋", "瓶", "箱", "只", "组");
    private static final Set<String> HTML_TAGS = Set.of("p", "br", "ul", "ol", "li", "b", "strong", "i", "em", "u", "h3");
    private static final Pattern HTML_TAG = Pattern.compile("(?i)</?([a-z0-9]+)[^>]*>");
    private static final String SYSTEM = """
            你是商城商家的商品录入助手。根据对话、当前表单和附件，提取能确定的商品信息。
            只输出一个 JSON 对象，不要 Markdown，不要解释。
            结构如下，不确定的字段直接省略，不要编造：
            {
              "reply": "用中文简短说明填了什么、还缺什么",
              "product": {
                "productName": "不超过60字",
                "categoryId": 0,
                "spuNo": "货号",
                "fakeSold": 0,
                "specImages": true,
                "specs": [
                  {
                    "skuName": "规格名",
                    "parentName": "上级规格名，一级留空字符串",
                    "unitName": "份",
                    "salePrice": 0,
                    "marketPrice": null,
                    "memberPrice": null,
                    "stock": 0
                  }
                ],
                "detail": "商品介绍 HTML，不要写图片地址",
                "services": "服务承诺 HTML"
              },
              "albumImageIndexes": [1],
              "detailImageIndexes": [2]
            }
            规则：
            - 附件里的文字要尽量填进商品名称、类目、货号、已售、规格、价格、库存、介绍和服务承诺。
            - categoryId 只能从当前表单里的类目列表选择，对不上就省略。
            - specs 一旦给出，就是完整规格列表，会替换现有规格。最多两级。有下级的行是分组，不要填单位、价格和库存。没有下级的行必须有单位、大于 0 的售价和非负库存。
            - 单位只能是：份、斤、盒、袋、瓶、箱、只、组。按重量卖用斤，按件卖用份。
            - 划线价必须大于或等于售价，否则填 null。会员价必须大于 0 且不高于售价，否则填 null。
            - detail 和 services 只用 p、br、ul、ol、li、b、strong、i、em、u、h3，不要自己写 img。
            - albumImageIndexes 放商品主图、商品图片的编号。detailImageIndexes 放详情、介绍配图的编号。编号见用户消息，从 1 开始。同一张不要两边都放。表格、图标、装饰图不要放。
            - 商家只是在问问题、还没给出可填写的信息时，product 省略，在 reply 里回答。
            """;

    private final ObjectMapper json;
    private final FileStorageConfig storage;
    private final RestClient deepseek;
    private final String apiKey;

    public ProductAssistantService(
            ObjectMapper json,
            FileStorageConfig storage,
            @Value("${emall.deepseek-api-key:}") String apiKey
    ) {
        this.json = json;
        this.storage = storage;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(180));
        this.deepseek = RestClient.builder()
                .baseUrl("https://api.deepseek.com")
                .requestFactory(factory)
                .build();
    }

    public Map<String, Object> chat(
            HttpServletRequest request,
            String message,
            String draft,
            String history,
            List<MultipartFile> files
    ) {
        if (apiKey.isBlank()) {
            throw new ApiException(503, "商品助手还没有配置");
        }
        String text = message == null ? "" : message.trim();
        List<Uploaded> uploaded = readFiles(files);
        if (text.isBlank() && uploaded.isEmpty()) {
            throw new ApiException(400, "请输入商品信息，或上传附件");
        }
        if (text.isBlank()) {
            text = "请根据附件识别内容，并填写三步商品信息。";
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.addAll(historyMessages(history));
        messages.add(Map.of("role", "user", "content", userContent(text, draft, uploaded)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-flash");
        body.put("messages", messages);
        body.put("thinking", Map.of("type", "disabled"));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", 4096);

        JsonNode root = postJson("/chat/completions", body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return toResult(request, content, draft, uploaded);
    }

    private List<Map<String, Object>> userContent(String text, String draft, List<Uploaded> uploaded) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前表单：\n").append(clip(draft == null ? "{}" : draft, 12000));
        prompt.append("\n\n商家说：\n").append(clip(text, 4000));
        if (!uploaded.isEmpty()) {
            prompt.append("\n\n附件：");
            for (int i = 0; i < uploaded.size(); i++) {
                prompt.append("\n").append(i + 1).append(". ").append(uploaded.get(i).name());
            }
        }
        List<Picture> pictures = pictures(uploaded);
        if (!pictures.isEmpty()) {
            prompt.append("\n\n附件中的图片编号：");
            for (int i = 0; i < pictures.size(); i++) {
                prompt.append("\n").append(i + 1).append(". ").append(pictures.get(i).label());
            }
            prompt.append("\n请把商品图编号写入 albumImageIndexes，把详情图编号写入 detailImageIndexes。");
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt.toString()));
        for (Uploaded file : uploaded) {
            content.add(Map.of("type", "file", "file_id", file.fileId()));
        }
        return content;
    }

    private Map<String, Object> toResult(
            HttpServletRequest request,
            String content,
            String draft,
            List<Uploaded> uploaded
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode parsed;
        try {
            parsed = json.readTree(extractJson(content));
        } catch (Exception exception) {
            result.put("reply", clip(content.isBlank() ? "没有识别到可以填写的内容" : content, 1000));
            return result;
        }
        result.put("reply", clip(parsed.path("reply").asText("已根据内容整理商品信息，请核对三步表单。"), 1000));
        JsonNode product = parsed.path("product");
        ObjectNode clean = product.isObject() ? cleanProduct((ObjectNode) product, draft) : json.createObjectNode();
        placePictures(request, clean, parsed, pictures(uploaded));
        if (!clean.isEmpty()) {
            result.put("product", clean);
        }
        return result;
    }

    private ObjectNode cleanProduct(ObjectNode product, String draft) {
        ObjectNode clean = json.createObjectNode();
        if (product.hasNonNull("productName")) {
            String name = product.path("productName").asText("").trim();
            if (!name.isEmpty()) {
                clean.put("productName", name.length() > 60 ? name.substring(0, 60) : name);
            }
        }
        Long categoryId = categoryId(product, draft);
        if (categoryId != null) {
            clean.put("categoryId", categoryId);
        }
        if (product.hasNonNull("spuNo")) {
            String spuNo = product.path("spuNo").asText("").trim();
            if (!spuNo.isEmpty() && spuNo.length() <= 32) {
                clean.put("spuNo", spuNo);
            }
        }
        Integer fakeSold = integer(product.get("fakeSold"));
        if (fakeSold != null && fakeSold >= 0) {
            clean.put("fakeSold", fakeSold);
        }
        if (product.has("specImages") && product.get("specImages").isBoolean()) {
            clean.put("specImages", product.get("specImages").asBoolean());
        }
        JsonNode specs = cleanSpecs(product.path("specs"));
        if (specs != null) {
            clean.set("specs", specs);
        }
        String detail = cleanHtml(product.path("detail").asText(""));
        if (detail != null) {
            clean.put("detail", detail);
        }
        String services = cleanHtml(product.path("services").asText(""));
        if (services != null) {
            clean.put("services", services);
        }
        return clean;
    }

    private Long categoryId(JsonNode product, String draft) {
        if (!product.hasNonNull("categoryId") || !product.get("categoryId").isNumber()) {
            return null;
        }
        long id = product.get("categoryId").asLong();
        try {
            JsonNode categories = json.readTree(draft == null ? "{}" : draft).path("categories");
            if (!categories.isArray()) {
                return null;
            }
            for (JsonNode category : categories) {
                if (category.path("id").asLong() == id) {
                    return id;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private JsonNode cleanSpecs(JsonNode specs) {
        if (!specs.isArray() || specs.isEmpty()) {
            return null;
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode spec : specs) {
            String name = spec.path("skuName").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            ObjectNode row = json.createObjectNode();
            row.put("skuName", name.length() > 40 ? name.substring(0, 40) : name);
            row.put("parentName", spec.path("parentName").asText("").trim());
            row.put("unitName", unit(spec.path("unitName").asText("")));
            putPrice(row, "salePrice", spec.get("salePrice"));
            putPrice(row, "marketPrice", spec.get("marketPrice"));
            putPrice(row, "memberPrice", spec.get("memberPrice"));
            Integer stock = integer(spec.get("stock"));
            if (stock != null && stock >= 0) {
                row.put("stock", stock);
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Set<String> childNames = rows.stream()
                .filter(row -> !row.path("parentName").asText("").isEmpty())
                .map(row -> row.path("skuName").asText(""))
                .collect(java.util.stream.Collectors.toSet());
        for (ObjectNode row : rows) {
            if (childNames.contains(row.path("parentName").asText(""))) {
                row.put("parentName", "");
            }
        }
        Set<String> parents = rows.stream()
                .map(row -> row.path("parentName").asText(""))
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        for (ObjectNode row : rows) {
            if (parents.contains(row.path("skuName").asText())) {
                row.remove("unitName");
                row.remove("salePrice");
                row.remove("marketPrice");
                row.remove("memberPrice");
                row.remove("stock");
            } else {
                BigDecimal sale = decimal(row.get("salePrice"));
                BigDecimal market = decimal(row.get("marketPrice"));
                BigDecimal member = decimal(row.get("memberPrice"));
                if (sale == null || market != null && market.compareTo(sale) < 0) {
                    row.remove("marketPrice");
                }
                if (sale == null || member != null && (member.signum() <= 0 || member.compareTo(sale) > 0)) {
                    row.remove("memberPrice");
                }
            }
        }
        return json.valueToTree(rows);
    }

    private List<Uploaded> readFiles(List<MultipartFile> files) {
        List<Uploaded> uploaded = new ArrayList<>();
        if (files == null) {
            return uploaded;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (uploaded.size() >= MAX_FILES) {
                throw new ApiException(400, "一次最多上传 4 个附件");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new ApiException(400, "单个附件不能超过 15MB");
            }
            String name = file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename();
            String purpose = purpose(name);
            if (purpose == null) {
                throw new ApiException(400, "附件只支持图片、PDF、Word、PPT、Excel 和文本");
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (Exception exception) {
                throw new ApiException(400, "附件读取失败");
            }
            String fileId = uploadToDeepSeek(name, purpose, bytes);
            uploaded.add(new Uploaded(name, fileId, picturesOf(name, bytes)));
        }
        return uploaded;
    }

    private String uploadToDeepSeek(String name, String purpose, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("purpose", purpose);
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        try {
            String response = deepseek.post()
                    .uri("/files")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = json.readTree(response == null ? "{}" : response);
            String id = root.path("id").asText("");
            if (id.isBlank()) {
                throw new ApiException(502, "附件上传到模型失败");
            }
            return id;
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("DeepSeek file upload failed: {}", exception.getStatusCode().value());
            throw new ApiException(502, deepseekMessage(exception));
        } catch (Exception exception) {
            log.warn("DeepSeek file upload failed");
            throw new ApiException(502, "附件识别服务暂时不可用");
        }
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        try {
            String response = deepseek.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return json.readTree(response == null ? "{}" : response);
        } catch (RestClientResponseException exception) {
            log.warn("DeepSeek chat failed: {}", exception.getStatusCode().value());
            throw new ApiException(502, deepseekMessage(exception));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("DeepSeek chat failed");
            throw new ApiException(502, "商品助手暂时不可用");
        }
    }

    private void placePictures(HttpServletRequest request, ObjectNode clean, JsonNode parsed, List<Picture> pictures) {
        if (pictures.isEmpty()) {
            return;
        }
        boolean classified = parsed.has("albumImageIndexes") || parsed.has("detailImageIndexes");
        List<Integer> album = indexes(parsed.path("albumImageIndexes"), pictures.size());
        List<Integer> detail = indexes(parsed.path("detailImageIndexes"), pictures.size());
        detail.removeAll(album);
        if (!classified && album.isEmpty() && detail.isEmpty()) {
            album.add(0);
            for (int i = 1; i < pictures.size(); i++) {
                detail.add(i);
            }
        }
        List<String> albumUrls = savePictures(request, pictures, album, 5);
        List<String> detailUrls = savePictures(request, pictures, detail, 5);
        if (!albumUrls.isEmpty()) {
            clean.set("imageUrls", json.valueToTree(albumUrls));
        }
        if (!detailUrls.isEmpty()) {
            String html = clean.path("detail").asText("");
            StringBuilder detailHtml = new StringBuilder(html);
            for (String url : detailUrls) {
                detailHtml.append("<p><img src=\"").append(escape(url)).append("\" alt=\"\"></p>");
            }
            clean.put("detail", detailHtml.toString());
        }
    }

    private static List<Picture> pictures(List<Uploaded> uploaded) {
        List<Picture> pictures = new ArrayList<>();
        for (Uploaded file : uploaded) {
            pictures.addAll(file.pictures());
        }
        return pictures;
    }

    private static List<Integer> indexes(JsonNode node, int size) {
        List<Integer> indexes = new ArrayList<>();
        if (!node.isArray()) {
            return indexes;
        }
        for (JsonNode item : node) {
            if (!item.isNumber()) {
                continue;
            }
            int index = item.asInt() - 1;
            if (index >= 0 && index < size && !indexes.contains(index)) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private List<String> savePictures(HttpServletRequest request, List<Picture> pictures, List<Integer> indexes, int limit) {
        List<String> urls = new ArrayList<>();
        for (int index : indexes) {
            if (urls.size() >= limit) {
                break;
            }
            String url = storePicture(request, pictures.get(index));
            if (url != null) {
                urls.add(url);
            }
        }
        return urls;
    }

    private String storePicture(HttpServletRequest request, Picture picture) {
        try {
            java.nio.file.Files.createDirectories(storage.uploadDir());
            String stored = java.util.UUID.randomUUID() + picture.extension();
            java.nio.file.Files.write(storage.uploadDir().resolve(stored), picture.bytes());
            return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/files/" + stored;
        } catch (Exception exception) {
            log.warn("Saving assistant image failed");
            return null;
        }
    }

    private List<Picture> picturesOf(String name, byte[] bytes) {
        List<Picture> pictures = new ArrayList<>();
        if (isAlbumImage(name)) {
            pictures.add(new Picture(name, bytes));
            return pictures;
        }
        String kind = extension(name);
        if (!kind.equals(".docx") && !kind.equals(".pptx") && !kind.equals(".xlsx")) {
            return pictures;
        }
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isEmbeddedImage(path) || pictures.size() >= 8) {
                    continue;
                }
                byte[] data = zip.readAllBytes();
                if (data.length < 2048 || data.length > 5L * 1024 * 1024) {
                    continue;
                }
                pictures.add(new Picture(name + " 的图片 " + (pictures.size() + 1), data, extension(path)));
            }
        } catch (Exception exception) {
            log.warn("Reading images inside attachment failed");
        }
        return pictures;
    }

    private static boolean isEmbeddedImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        boolean media = lower.startsWith("word/media/") || lower.startsWith("ppt/media/") || lower.startsWith("xl/media/");
        return media && isAlbumImage(lower);
    }

    private List<Map<String, Object>> historyMessages(String history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (history == null || history.isBlank()) {
            return messages;
        }
        try {
            JsonNode root = json.readTree(history);
            if (!root.isArray()) {
                return messages;
            }
            int from = Math.max(0, root.size() - 8);
            for (int i = from; i < root.size(); i++) {
                String role = root.get(i).path("role").asText("");
                String content = root.get(i).path("content").asText("").trim();
                if ((!"user".equals(role) && !"assistant".equals(role)) || content.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("content", clip(content, 2000));
                messages.add(item);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return messages;
    }

    private static String purpose(String name) {
        String extension = extension(name);
        return switch (extension) {
            case ".jpg", ".jpeg", ".png", ".gif", ".webp" -> "user_data";
            case ".txt", ".md", ".csv", ".json", ".xls", ".xlsx" -> "user_data";
            case ".pdf", ".doc", ".docx", ".ppt", ".pptx" -> "rich_layout_recognition";
            default -> null;
        };
    }

    private static boolean isAlbumImage(String name) {
        String extension = extension(name);
        return extension.equals(".jpg") || extension.equals(".jpeg") || extension.equals(".png") || extension.equals(".webp");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String unit(String raw) {
        String value = raw.trim();
        if (UNITS.contains(value)) {
            return value;
        }
        return switch (value) {
            case "个", "件", "包", "提" -> "份";
            case "公斤", "千克", "kg", "KG" -> "斤";
            case "g", "克" -> "份";
            default -> "份";
        };
    }

    private void putPrice(ObjectNode row, String field, JsonNode node) {
        BigDecimal value = decimal(node);
        if (value != null && value.signum() > 0) {
            row.put(field, value);
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }

    private static Integer integer(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private static String cleanHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "");
        if (!value.contains("<")) {
            StringBuilder paragraphs = new StringBuilder();
            for (String line : value.split("\\n+")) {
                String text = line.trim();
                if (!text.isEmpty()) {
                    paragraphs.append("<p>").append(escape(text)).append("</p>");
                }
            }
            value = paragraphs.toString();
        } else {
            Matcher matcher = HTML_TAG.matcher(value);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                out.append(value, last, matcher.start());
                String name = matcher.group(1).toLowerCase(Locale.ROOT);
                if (HTML_TAGS.contains(name)) {
                    if ("br".equals(name)) {
                        out.append("<br>");
                    } else if (matcher.group().startsWith("</")) {
                        out.append("</").append(name).append(">");
                    } else {
                        out.append("<").append(name).append(">");
                    }
                }
                last = matcher.end();
            }
            out.append(value.substring(last));
            value = out.toString().trim();
        }
        if (value.length() > 8000) {
            value = value.substring(0, 8000);
        }
        return value.isBlank() ? null : value;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String extractJson(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        int left = text.indexOf('{');
        int right = text.lastIndexOf('}');
        if (left >= 0 && right > left) {
            return text.substring(left, right + 1);
        }
        return text;
    }

    private static String deepseekMessage(RestClientResponseException exception) {
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exception.getResponseBodyAsByteArray());
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                return "商品助手暂时不可用：" + clip(message, 120);
            }
        } catch (Exception ignored) {
            return "商品助手暂时不可用";
        }
        return "商品助手暂时不可用";
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record Uploaded(String name, String fileId, List<Picture> pictures) {
    }

    private record Picture(String label, byte[] bytes, String extension) {
        private Picture(String label, byte[] bytes) {
            this(label, bytes, ProductAssistantService.extension(label));
        }
    }
}
