package com.journal.controller;

import com.journal.service.ExternalUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 上传外网 REST API
 *
 * 将 MongoDB 中已标注的论文数据合并旧数据，全量推送到外网 150 SQL Server。
 */
@RestController
@RequestMapping("/api/upload")
public class ExternalUploadController {

    private final ExternalUploadService uploadService;

    public ExternalUploadController(ExternalUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 预览待上传的已标注论文
     * GET /api/upload/preview
     */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview() {
        return ResponseEntity.ok(uploadService.preview());
    }

    /**
     * 执行上传到外网
     * POST /api/upload/execute
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute() {
        Map<String, Object> result = uploadService.execute();
        if (Boolean.FALSE.equals(result.get("success"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 回补 v3.0 迁移走的历史数据：归档集合（大写）→ 工作集合（小写），标记为已上传。
     * 幂等，重复调用不会产生副本。
     *
     * POST /api/upload/backfill?dryRun=true   只统计，不写入（默认）
     * POST /api/upload/backfill?dryRun=false  实际执行
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(uploadService.backfillFromArchive(dryRun));
    }

    /**
     * 查看 数据库状态（表列表 + 各表行数 + 前3条样例）
     * GET /api/upload/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = uploadService.get150Status();
        if (Boolean.FALSE.equals(result.get("success"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
