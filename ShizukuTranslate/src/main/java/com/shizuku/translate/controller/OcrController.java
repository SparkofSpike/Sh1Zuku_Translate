package com.shizuku.translate.controller;

import com.shizuku.translate.dto.OcrResponse;
import com.shizuku.translate.service.OcrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class OcrController {

    private final OcrService ocrService;

    public OcrController(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 上传图片进行 OCR 识别
     * 支持：竖排日文、横排日文
     */
    @PostMapping("/ocr")
    public ResponseEntity<OcrResponse> ocrImage(@RequestParam("image") MultipartFile file, @RequestParam(value = "polish", defaultValue = "true") boolean polish, @RequestParam(value = "threshold", defaultValue = "0.5") double threshold) {
        // 校验文件
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(OcrResponse.error("请上传有效的图片文件"));
        }

        // 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(OcrResponse.error("仅支持图片文件（JPEG/PNG等）"));
        }

        OcrResponse result = ocrService.processImage(file, polish, threshold);
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(502).body(result);
        }
    }

    /**
     * 检查 OCR 服务健康状态
     */
    @GetMapping("/ocr/health")
    public ResponseEntity<OcrResponse> health() {
        boolean healthy = ocrService.isHealthy();
        if (healthy) {
            return ResponseEntity.ok(OcrResponse.success("OCR 服务运行正常", 0));
        } else {
            return ResponseEntity.status(503)
                    .body(OcrResponse.error("OCR 服务不可用，请确认 Python OCR 微服务已启动"));
        }
    }
}
