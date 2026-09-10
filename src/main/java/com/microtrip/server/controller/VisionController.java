package com.microtrip.server.controller;

import com.microtrip.server.dto.VisionRequest;
import com.microtrip.server.dto.VisionResult;
import com.microtrip.server.security.JwtPrincipal;
import com.microtrip.server.service.VisionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 图像识别接口（需 Bearer）：POST /vision/recognize。
 * 与 Node 版路由、响应结构完全对齐；未配置密钥返回 501（不伪造结果）。
 */
@RestController
@RequestMapping({"/vision", "/api/v1/vision"})
public class VisionController {

    private final VisionService visionService;

    public VisionController(VisionService visionService) {
        this.visionService = visionService;
    }

    /** POST /vision/recognize → { labels, matches: { scenery, food } } */
    @PostMapping("/recognize")
    public VisionResult recognize(@Valid @RequestBody VisionRequest req,
                                  @AuthenticationPrincipal JwtPrincipal principal) {
        String city = req.city() == null ? "" : req.city();
        return visionService.recognize(req.imageBase64(), city);
    }
}
