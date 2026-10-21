package com.journal.controller;

import com.journal.model.Paper;
import com.journal.service.ContentGenerator;
import com.journal.service.ExcelImportService;
import com.journal.service.PaperFilters;

import com.journal.service.ExcelImportService.BatchImportResult;
import com.journal.service.ExcelImportService.ImportResult;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 论文管理 REST API
 */
@RestController
@RequestMapping("/api/papers")
public class PaperController {

    /** 工作集合（小写）：用户标注编辑的处理中数据 */
    private static final List<String> PAPER_COLLECTIONS = List.of(
            "domestic_papers",
            "intl_chinese_accounting",
            "intl_chinese_finance",
            "intl_accounting",
            "intl_finance");

    private static final String ALL = "all";

    private final ExcelImportService excelImportService;
    private final MongoTemplate mongoTemplate;
    private final ContentGenerator contentGenerator;

    public PaperController(ExcelImportService excelImportService, MongoTemplate mongoTemplate,
                           ContentGenerator contentGenerator) {
        this.excelImportService = excelImportService;
        this.mongoTemplate = mongoTemplate;
        this.contentGenerator = contentGenerator;
    }

    // ==================== Excel 导入 ====================

    /**
     * 上传单个 Excel 文件导入
     * POST /api/papers/import
     * Form-data: file=xxx.xlsx, collection=集合名（可选）
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String collection,
            @RequestParam(defaultValue = "false") boolean legacy) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择文件"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "只支持 .xlsx / .xls"));
        }

        ImportResult result = legacy
                ? excelImportService.importLegacy(file, collection)
                : excelImportService.importExcel(file, collection);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.getErrors().isEmpty());
        resp.put("file", filename);
        resp.put("collection", result.getCollection());
        resp.put("inserted", result.getInserted());
        resp.put("skipped", result.getSkipped());
        resp.put("errors", result.getErrors());
        return ResponseEntity.ok(resp);
    }

    /**
     * 批量导入：指定本地目录路径，自动扫描所有 Excel
     * POST /api/papers/import/batch
     * Body: { "dirPath": "./内网导入/导入内网" }
     */
    @PostMapping("/import/batch")
    public ResponseEntity<Map<String, Object>> importBatch(@RequestBody Map<String, String> body) {
        String dirPath = body.get("dirPath");
        if (dirPath == null || dirPath.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请提供目录路径"));
        }

        BatchImportResult batch = excelImportService.importDirectory(dirPath);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", batch.getErrors().isEmpty());
        resp.put("totalInserted", batch.getTotalInserted());
        resp.put("results", batch.getResults());
        resp.put("errors", batch.getErrors());
        return ResponseEntity.ok(resp);
    }

    // ==================== 数据管理 ====================

    /**
     * 清空集合
     * DELETE /api/papers/collection?name=domestic_papers
     */
    @DeleteMapping("/collection")
    public ResponseEntity<Map<String, Object>> dropCollection(@RequestParam String name) {
        // 只允许清空五个论文工作集合。原先没有白名单，任何集合名都能传进来删掉，
        // 包括 datahub_databases（数据库目录）和大写归档集合。
        if (!PAPER_COLLECTIONS.contains(name)) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "只允许清空论文工作集合，不能清空: " + name));
        }
        mongoTemplate.dropCollection(name);
        return ResponseEntity.ok(Map.of("success", true, "collection", name, "message", "已清空"));
    }

    /**
     * 按来源文件删除（用于清理测试数据）
     * DELETE /api/papers/bySource?collection=domestic_papers&sourceFile=中文期刊
     */
    @DeleteMapping("/bySource")
    public ResponseEntity<Map<String, Object>> deleteBySource(
            @RequestParam String collection,
            @RequestParam String sourceFile) {
        org.springframework.data.mongodb.core.query.Query query =
                new org.springframework.data.mongodb.core.query.Query(
                        org.springframework.data.mongodb.core.query.Criteria.where("sourceFile").is(sourceFile));
        long deleted = mongoTemplate.findAllAndRemove(query, Paper.class, collection).size();
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted, "collection", collection));
    }

    // ==================== 软删除 / 恢复（v4.1）====================

    /**
     * 删除论文（软删除，打墓碑）。抓取到不合要求的数据时用。
     * POST /api/papers/delete
     * Body: { "collection": "domestic_papers", "ids": ["id1", "id2"] }
     *
     * 用 POST 而不是 DELETE：批量删要带 id 列表，部分代理会丢掉 DELETE 的请求体。
     *
     * 删除后该论文：从论文列表、搜索、导出、标注待办中消失，
     * 下次上传外网也不再推送（连归档集合里的同一篇副本一起拦掉），
     * 可在回收站恢复。
     */
    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> softDelete(@RequestBody Map<String, Object> body) {
        String collection = (String) body.get("collection");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");

        if (collection == null || !PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未选择任何论文"));
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long modified = mongoTemplate.updateMulti(
                new Query(Criteria.where("_id").in(ids)),
                new Update().set("deleted", true).set("deletedAt", now),
                collection).getModifiedCount();

        // 已上传过 150 的论文被删掉，得让用户知道 150 上还留着，要重新上传才会同步掉
        long uploadedAffected = mongoTemplate.count(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").in(ids),
                        Criteria.where("uploaded").is(true))),
                collection);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("collection", collection);
        resp.put("requested", ids.size());
        resp.put("deleted", modified);
        resp.put("uploadedAffected", uploadedAffected);
        return ResponseEntity.ok(resp);
    }

    /**
     * 恢复已删除的论文（清掉墓碑）
     * POST /api/papers/restore
     * Body: { "collection": "domestic_papers", "ids": ["id1", "id2"] }
     */
    @PostMapping("/restore")
    public ResponseEntity<Map<String, Object>> restore(@RequestBody Map<String, Object> body) {
        String collection = (String) body.get("collection");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");

        if (collection == null || !PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未选择任何论文"));
        }

        long modified = mongoTemplate.updateMulti(
                new Query(Criteria.where("_id").in(ids)),
                new Update().unset("deleted").unset("deletedAt"),
                collection).getModifiedCount();

        return ResponseEntity.ok(Map.of("success", true, "collection", collection,
                "requested", ids.size(), "restored", modified));
    }

    /**
     * 回收站：分页查看已删除的论文
     * GET /api/papers/deleted?collection=all&page=0&size=20
     */
    @GetMapping("/deleted")
    public ResponseEntity<Map<String, Object>> deletedList(
            @RequestParam String collection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (collection == null || (!ALL.equals(collection) && !PAPER_COLLECTIONS.contains(collection))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }

        List<Paper> all = fetchPapers(collection, true);
        // 最近删的排前面（deletedAt 为 yyyy-MM-dd HH:mm:ss，字符串比较即时间比较）
        all.sort(java.util.Comparator.comparing(
                Paper::getDeletedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));

        List<Paper> papers = all.stream()
                .skip((long) page * size).limit(size)
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", papers);
        resp.put("total", all.size());
        resp.put("page", page);
        resp.put("size", size);
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    // ==================== 数据查询 ====================

    /**
     * 获取所有集合名称
     * GET /api/papers/collections
     */
    @GetMapping("/collections")
    public ResponseEntity<List<Map<String, Object>>> collections() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : PAPER_COLLECTIONS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            // 不含已删除的，否则集合下拉里的条数和列表实际能看到的对不上
            m.put("count", mongoTemplate.count(new Query(PaperFilters.notDeleted()), name));
            m.put("deletedCount", mongoTemplate.count(new Query(PaperFilters.deleted()), name));
            list.add(m);
        }
        return ResponseEntity.ok(list);
    }

    /**
     * 分页查询论文（未关联数据库的排前面，方便优先处理）
     * GET /api/papers?collection=domestic_papers&page=0&size=20
     * collection=all 时跨全部五个集合查询
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam String collection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<Paper> all = fetchPapers(collection);
        List<Paper> papers = all.stream()
                .sorted(java.util.Comparator.comparing(this::isAnnotated))  // 未标注(false)在前
                .skip((long) page * size)
                .limit(size)
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", papers);
        resp.put("total", all.size());
        resp.put("page", page);
        resp.put("size", size);
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    /** 读取论文并在每条上填充所属集合名；collection=all 时合并五个集合。排除已删除的 */
    private List<Paper> fetchPapers(String collection) {
        return fetchPapers(collection, false);
    }

    /**
     * 读取论文。deletedOnly=true 时只取回收站里的墓碑，否则只取未删除的。
     */
    private List<Paper> fetchPapers(String collection, boolean deletedOnly) {
        List<String> cols = ALL.equals(collection) ? PAPER_COLLECTIONS : List.of(collection);
        Query q = new Query(deletedOnly ? PaperFilters.deleted() : PaperFilters.notDeleted());
        List<Paper> result = new ArrayList<>();
        for (String col : cols) {
            List<Paper> papers = mongoTemplate.find(q, Paper.class, col);
            papers.forEach(p -> p.setCollection(col));
            result.addAll(papers);
        }
        return result;
    }

    /** 已处理过关联 = relateDBs 非空，或已人工确认无关联 */
    private boolean isAnnotated(Paper p) {
        boolean hasRelate = p.getRelateDBs() != null && !p.getRelateDBs().isEmpty();
        return hasRelate || Boolean.TRUE.equals(p.getNoRelateDbs());
    }

    /**
     * 搜索论文
     * GET /api/papers/search?collection=xxx&keyword=xxx
     */
    @GetMapping("/search")
    public ResponseEntity<List<Paper>> search(
            @RequestParam String collection,
            @RequestParam String keyword) {
        // 简单实现：全量查找后过滤（小数据量够用）
        List<Paper> result = fetchPapers(collection).stream()
                .filter(p -> p.getTitle() != null && p.getTitle().contains(keyword))
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 高级搜索：多字段组合条件
     * POST /api/papers/searchAdvanced
     * Body 所有条件均可选，同时给出的条件之间是"且"的关系：
     * {
     *   "collection": "domestic_papers",   // 必填；传 "all" 跨全部集合搜索
     *   "title": "会计",                    // 标题包含
     *   "abstractText": "数字化",           // 摘要/内容包含
     *   "authors": "张三",                  // 作者包含
     *   "keywords": "创新",                 // 关键词包含
     *   "journalName": "经济研究",           // 期刊名包含
     *   "publishDateFrom": "2026-01",       // 发表年月区间起（含），格式 yyyy-MM
     *   "publishDateTo": "2026-06",         // 发表年月区间止（含）
     *   "releaseDateFrom": "2026-07-01",    // 转换日期区间起（含）
     *   "releaseDateTo": "2026-07-17",      // 转换日期区间止（含）
     *   "relateStatus": "unrelated",        // 关联状态: all/unrelated/related/none/reannotate
     *   "uploadStatus": "uploaded"          // 上传状态: all/uploaded/notUploaded/dirty
     * }
     */
    @PostMapping("/searchAdvanced")
    public ResponseEntity<Map<String, Object>> searchAdvanced(@RequestBody Map<String, String> cond) {
        String collection = cond.get("collection");
        if (collection == null || (!ALL.equals(collection) && !PAPER_COLLECTIONS.contains(collection))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }

        List<Paper> result = fetchPapers(collection).stream()
                .filter(p -> contains(p.getTitle(), cond.get("title")))
                .filter(p -> contains(p.getAbstractText(), cond.get("abstractText")))
                .filter(p -> contains(p.getAuthors(), cond.get("authors")))
                .filter(p -> contains(p.getKeywords(), cond.get("keywords")))
                .filter(p -> contains(p.getJournalName(), cond.get("journalName")))
                .filter(p -> inPublishYmRange(p.getPublishDate(), cond.get("publishDateFrom"), cond.get("publishDateTo")))
                .filter(p -> inDateRange(p.getReleaseDate(), cond.get("releaseDateFrom"), cond.get("releaseDateTo")))
                .filter(p -> matchRelateStatus(p, cond.get("relateStatus")))
                .filter(p -> matchUploadStatus(p, cond.get("uploadStatus")))
                .sorted(java.util.Comparator.comparing(this::isAnnotated))
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", result);
        resp.put("total", result.size());
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    // ==================== 导出（从处理完的大写集合读取） ====================

    /** 导出用文件名 */
    private static final Map<String, String> EXPORT_FILENAMES = Map.of(
            "domestic_papers", "DomesticPaper",
            "intl_chinese_accounting", "InternationalChinesePaper_Accounting",
            "intl_chinese_finance", "InternationalChinesePaper_Finance",
            "intl_accounting", "Internationalpaper_Accounting",
            "intl_finance", "Internationalpaper_Finance");

    /** 导出排序：publishDate 降序优先，同日内 relateDBs 非空排前面 */
    private java.util.Comparator<Paper> exportSort() {
        return java.util.Comparator
                .comparing(this::pubYmSortKey, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(java.util.Comparator.comparing(this::hasRelateDBs).reversed());
    }

    private boolean hasRelateDBs(Paper p) {
        return p.getRelateDBs() != null && !p.getRelateDBs().isEmpty();
    }

    private Integer pubYmSortKey(Paper p) {
        return parsePublishYm(p.getPublishDate());
    }

    private boolean isInternational(String collection) {
        return !"domestic_papers".equals(collection);
    }

    /**
     * 导出预览：从旧集合（小写）读取，排序后分页返回
     * GET /api/papers/export-preview?collection=domestic_papers&page=0&size=20
     */
    @GetMapping("/export-preview")
    public ResponseEntity<Map<String, Object>> exportPreview(
            @RequestParam String collection,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<Paper> all = fetchPapers(collection);
        all.sort(exportSort());

        List<Paper> papers = all.stream()
                .skip((long) page * size).limit(size)
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", papers);
        resp.put("total", all.size());
        resp.put("page", page);
        resp.put("size", size);
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    /**
     * 导出 Excel：从旧集合（小写）读取，生成 .xlsx 下载
     * GET /api/papers/export?collection=domestic_papers
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportExcel(@RequestParam String collection) {
        List<Paper> papers = fetchPapers(collection);
        papers.sort(exportSort());

        boolean isIntl = isInternational(collection);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            String sheetName = EXPORT_FILENAMES.getOrDefault(collection, collection);
            Sheet sheet = wb.createSheet(sheetName);

            Font headerFont = wb.createFont();
            headerFont.setBold(true);

            String[] headers = isIntl
                    ? new String[]{"PaperTitle", "JournalName", "JournalAbnName", "PublishDate", "ReleaseDate", "Content", "Type", "RelateDBs", "PaperID"}
                    : new String[]{"PaperTitle", "JournalName", "PublishDate", "ReleaseDate", "Content", "RelateDBs", "PaperID"};

            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                org.apache.poi.ss.usermodel.CellStyle style = wb.createCellStyle();
                style.setFont(headerFont);
                cell.setCellStyle(style);
            }

            for (int i = 0; i < papers.size(); i++) {
                Paper p = papers.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(p.getTitle() != null ? p.getTitle() : "");
                row.createCell(1).setCellValue(p.getJournalName() != null ? p.getJournalName() : "");
                if (isIntl) {
                    row.createCell(2).setCellValue(p.getJournalAbbName() != null ? p.getJournalAbbName() : "");
                    row.createCell(3).setCellValue(p.getPublishDate() != null ? p.getPublishDate() : "");
                    row.createCell(4).setCellValue(p.getReleaseDate() != null ? p.getReleaseDate() : "");
                    row.createCell(5).setCellValue(p.getContent() != null ? p.getContent() : "");
                    row.createCell(6).setCellValue(p.getType() != null ? p.getType() : "");
                    row.createCell(7).setCellValue(p.getRelateDBs() != null ? p.getRelateDBs() : "");
                    row.createCell(8).setCellValue(p.getPaperId() != null ? p.getPaperId() : "");
                } else {
                    row.createCell(2).setCellValue(p.getPublishDate() != null ? p.getPublishDate() : "");
                    row.createCell(3).setCellValue(p.getReleaseDate() != null ? p.getReleaseDate() : "");
                    row.createCell(4).setCellValue(p.getContent() != null ? p.getContent() : "");
                    row.createCell(5).setCellValue(p.getRelateDBs() != null ? p.getRelateDBs() : "");
                    row.createCell(6).setCellValue(p.getPaperId() != null ? p.getPaperId() : "");
                }
            }

            int colWidth = 5000;
            for (int i = 0; i < headers.length; i++) {
                sheet.setColumnWidth(i, colWidth);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            byte[] bytes = bos.toByteArray();

            String filename = sheetName + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 论文编辑 ====================

    /** 可直接编辑的字段（content 不在其中——它由字段自动生成） */
    private static final List<String> EDITABLE_FIELDS = List.of(
            "title", "authors", "journalName", "publishDate", "releaseDate",
            "keywords", "abstractText", "sourceUrl", "paperId", "type", "journalAbbName");

    /**
     * 实时预览：根据传入字段生成 Content HTML，不保存任何数据
     * POST /api/papers/preview
     * Body: { "collection": "domestic_papers", "title": "...", "authors": "...", ... }
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, String> body) {
        String collection = body.get("collection");
        if (collection == null || !PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        return ResponseEntity.ok(Map.of("success", true, "content", generateContent(collection, body)));
    }

    /**
     * 保存论文字段修改，并自动重新生成 Content HTML
     * PUT /api/papers/update
     * Body: { "collection": "...", "id": "...", 以及 EDITABLE_FIELDS 中的字段 }
     */
    @PutMapping("/update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, String> body) {
        String collection = body.get("collection");
        String id = body.get("id");
        if (collection == null || !PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (id == null || id.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少论文 id"));
        }
        if (body.get("title") == null || body.get("title").trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "标题不能为空"));
        }

        String content = generateContent(collection, body);
        Update update = new Update().set("content", content);
        for (String field : EDITABLE_FIELDS) {
            if (body.containsKey(field)) {
                update.set(field, body.get(field) == null ? "" : body.get(field).trim());
            }
        }
        // 已删除的论文不允许编辑（列表里看不到，只可能是并发操作或脏请求）
        long matched = mongoTemplate.updateFirst(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").is(id),
                        PaperFilters.notDeleted())),
                update, collection).getMatchedCount();

        if (matched == 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未找到论文（或已被删除）: " + id));
        }
        return ResponseEntity.ok(Map.of("success", true, "id", id, "content", content));
    }

    /** 按集合类型选用对应模板生成 Content HTML */
    private String generateContent(String collection, Map<String, String> f) {
        if ("domestic_papers".equals(collection)) {
            return contentGenerator.generateDomestic(
                    f.get("title"), f.get("journalName"), f.get("publishDate"),
                    f.get("authors"), f.get("abstractText"), f.get("keywords"));
        }
        return contentGenerator.generateInternational(
                f.get("title"), f.get("journalName"), f.get("publishDate"),
                f.get("authors"), f.get("abstractText"), f.get("keywords"), f.get("sourceUrl"));
    }

    /** 条件为空 = 不过滤；否则字段需包含关键字（字段为 null 视为不匹配） */
    private boolean contains(String field, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return true;
        return field != null && field.toLowerCase().contains(keyword.trim().toLowerCase());
    }

    /** releaseDate 为 yyyy-MM-dd 格式，字符串比较即日期比较 */
    private boolean inDateRange(String date, String from, String to) {
        boolean fromOk = from == null || from.trim().isEmpty();
        boolean toOk = to == null || to.trim().isEmpty();
        if (fromOk && toOk) return true;
        if (date == null || date.isEmpty()) return false;
        if (!fromOk && date.compareTo(from.trim()) < 0) return false;
        if (!toOk && date.compareTo(to.trim()) > 0) return false;
        return true;
    }

    /**
     * 发表年月区间过滤。from/to 格式 yyyy-MM。
     * publishDate 原文格式不统一，解析出 yyyyMM 数值后比较；解析不出的论文在给定区间条件时被排除。
     */
    private boolean inPublishYmRange(String publishDate, String from, String to) {
        Integer fromYm = toYm(from);
        Integer toYmV = toYm(to);
        if (fromYm == null && toYmV == null) return true;
        Integer ym = parsePublishYm(publishDate);
        if (ym == null) return false;
        if (fromYm != null && ym < fromYm) return false;
        if (toYmV != null && ym > toYmV) return false;
        return true;
    }

    /** "2026-04" -> 202604 */
    private Integer toYm(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String[] parts = s.trim().split("-");
        if (parts.length != 2) return null;
        try {
            return Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String[] EN_MONTHS = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};
    private static final String[] EN_MONTHS_ABBR = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};

    /**
     * 从发表日期原文解析 yyyyMM：
     * - 国内 "2026年04期" / "2026年第4期" -> 202604（期号近似当月份，月刊准确，季刊/双月刊有偏差）
     * - 国际 "Volume 116, June 2026" / "06 February 2026" -> 202606 / 202602
     * - 缩写格式 "6-Jun-26" -> 202606（两位年份按 20xx 处理）
     * - 只有年份时按 1 月处理；解析失败返回 null
     */
    private Integer parsePublishYm(String s) {
        if (s == null || s.isEmpty()) return null;
        java.util.regex.Matcher cn = java.util.regex.Pattern.compile("(\\d{4})年(?:第)?(\\d{1,2})").matcher(s);
        if (cn.find()) {
            int month = Integer.parseInt(cn.group(2));
            return Integer.parseInt(cn.group(1)) * 100 + Math.min(Math.max(month, 1), 12);
        }
        String lower = s.toLowerCase();
        // 年份：优先 4 位；否则找 "-26" 这类结尾的两位年份，按 20xx 处理
        Integer year = null;
        java.util.regex.Matcher y4 = java.util.regex.Pattern.compile("(\\d{4})").matcher(s);
        if (y4.find()) {
            year = Integer.parseInt(y4.group(1));
        } else {
            java.util.regex.Matcher y2 = java.util.regex.Pattern.compile("[-\\s](\\d{2})\\s*$").matcher(s);
            if (y2.find()) year = 2000 + Integer.parseInt(y2.group(1));
        }
        if (year == null) return null;
        for (int i = 0; i < EN_MONTHS.length; i++) {
            if (lower.contains(EN_MONTHS[i])) return year * 100 + (i + 1);
        }
        // 全称没匹配上再试缩写（如 "6-Jun-26"）
        for (int i = 0; i < EN_MONTHS_ABBR.length; i++) {
            if (lower.contains(EN_MONTHS_ABBR[i])) return year * 100 + (i + 1);
        }
        return year * 100 + 1;
    }

    /** 关联状态过滤: all=全部, unrelated=未关联, related=已关联, none=已确认无关联, reannotate=待重新标注 */    private boolean matchRelateStatus(Paper p, String status) {
        if (status == null || status.isEmpty() || status.equals("all")) return true;
        boolean hasRelate = p.getRelateDBs() != null && !p.getRelateDBs().isEmpty();
        boolean confirmedNone = Boolean.TRUE.equals(p.getNoRelateDbs());
        switch (status) {
            case "related":    return hasRelate;
            case "none":       return confirmedNone;
            case "unrelated":  return !hasRelate && !confirmedNone;
            case "reannotate": return Boolean.TRUE.equals(p.getReAnnotate());
            default:           return true;
        }
    }

    /** 上传状态过滤: all=全部, uploaded=已上传, notUploaded=未上传, dirty=已上传待更新 */
    private boolean matchUploadStatus(Paper p, String status) {
        if (status == null || status.isEmpty() || status.equals("all")) return true;
        boolean uploaded = Boolean.TRUE.equals(p.getUploaded());
        switch (status) {
            case "uploaded":    return uploaded;
            case "notUploaded": return !uploaded;
            case "dirty":       return uploaded && Boolean.TRUE.equals(p.getUploadDirty());
            default:            return true;
        }
    }
}
