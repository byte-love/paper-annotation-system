package com.journal.service;

import com.journal.model.Paper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Excel 导入 + 转换服务
 *
 * 转换规则：
 * 1. 识别输入格式（中文/英文期刊）
 * 2. 字段映射：标题→PaperTitle, 分类→JournalName, 期刊→PublishDate, _id→PaperID
 * 3. 生成 Content HTML（作者/摘要/关键字按模板拼接）
 * 4. 添加 ReleaseDate（转换当天日期）
 * 5. 添加 RelateDBs（空值，人工后补）
 * 6. 添加 Type / JournalAbbName（国际论文，根据期刊名查映射表）
 * 7. 分集合写入 MongoDB
 */
@Slf4j
@Service
public class ExcelImportService {

    private final MongoTemplate mongoTemplate;
    private final ContentGenerator contentGenerator;
    private final JournalMetadataService journalMeta;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 文件名 → 集合名 映射 */
    private static final Map<String, String> FILE_TO_COLLECTION = new LinkedHashMap<>();
    static {
        FILE_TO_COLLECTION.put("DomesticPaper", "domestic_papers");
        FILE_TO_COLLECTION.put("InternationalChinesePaper_Accounting", "intl_chinese_accounting");
        FILE_TO_COLLECTION.put("InternationalChinesePaper_Finance", "intl_chinese_finance");
        FILE_TO_COLLECTION.put("Internationalpaper_Accounting", "intl_accounting");
        FILE_TO_COLLECTION.put("Internationalpaper_Finance", "intl_finance");
    }

    public ExcelImportService(MongoTemplate mongoTemplate, ContentGenerator contentGenerator,
                              JournalMetadataService journalMeta) {
        this.mongoTemplate = mongoTemplate;
        this.contentGenerator = contentGenerator;
        this.journalMeta = journalMeta;
    }

    // ==================== 新转换导入 ====================

    /**
     * 导入单个 Excel 文件（走转换逻辑）
     */
    public ImportResult importExcel(MultipartFile file, String collectionName) {
        String filename = file.getOriginalFilename();
        log.info("导入文件: {}", filename);

        ImportResult result = new ImportResult();

        try (InputStream is = file.getInputStream()) {
            Workbook workbook = filename != null && filename.endsWith(".xls")
                    ? new HSSFWorkbook(is) : new XSSFWorkbook(is);

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> headerMap = buildHeaderMap(headerRow);
            log.info("  表头: {}", headerMap.keySet());

            // 识别格式：中文 or 英文
            boolean isChinese = isChineseFormat(sheet, headerMap);

            // 按集合分组，支持一个文件拆分到多个集合
            Map<String, List<Paper>> groupedPapers = new LinkedHashMap<>();
            String today = LocalDate.now().format(DATE_FMT);
            DedupeContext dedupe = new DedupeContext();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) continue;

                try {
                    Paper paper = convertRow(row, headerMap, isChinese, today, i + 1);

                    // 确定目标集合
                    String coll = collectionName != null ? collectionName
                            : resolveCollection(isChinese, paper.getType(),
                                    journalMeta.isInternationalChinese(paper.getTitle(), paper.getAbstractText()));

                    if (isDuplicate(paper, coll, dedupe, i + 1, result)) continue;

                    groupedPapers.computeIfAbsent(coll, k -> new ArrayList<>()).add(paper);
                } catch (Exception e) {
                    result.addError("第" + (i + 1) + "行: " + e.getMessage());
                    log.error("  行{} 转换失败", i + 1, e);
                }
            }

            // 批量写入各集合
            int totalInserted = 0;
            List<String> targetCollections = new ArrayList<>();
            for (Map.Entry<String, List<Paper>> entry : groupedPapers.entrySet()) {
                String coll = entry.getKey();
                List<Paper> papers = entry.getValue();
                if (!papers.isEmpty()) {
                    mongoTemplate.insert(papers, coll);
                    totalInserted += papers.size();
                    targetCollections.add(coll + "(" + papers.size() + "条)");
                    log.info("  {}: {} 条", coll, papers.size());
                }
            }
            result.setInserted(totalInserted);
            result.setCollection(String.join(", ", targetCollections));
            log.info("  总计: {} 条 → {}", totalInserted, result.getCollection());

            workbook.close();
        } catch (Exception e) {
            log.error("导入失败", e);
            result.getErrors().add("文件读取失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 转换一行数据
     */
    private Paper convertRow(Row row, Map<String, Integer> hdr, boolean isChinese,
                             String today, int rowNum) {
        // 提取输入字段
        String title = getByHeader(row, hdr, "标题", "PaperTitle");
        String authors = getByHeader(row, hdr, "作者", "Authors");
        String journalName = getByHeader(row, hdr, "分类", "JournalName", "期刊名称");
        String publishDate = getByHeader(row, hdr, "年月", "期刊", "PublishDate");
        String abstractText = getByHeader(row, hdr, "摘要", "Abstract");
        String keywords = getByHeader(row, hdr, "关键字", "关键词", "Keywords");
        String sourceUrl = getByHeader(row, hdr, "URL", "原文链接");
        String paperId = getByHeader(row, hdr, "_id", "doi", "PaperID");

        // 清理期刊名（中文可能有《》）
        String cleanJournal = journalName != null ? journalName.replace("《", "").replace("》", "").trim() : "";

        // 生成 Content HTML
        String content;
        if (isChinese) {
            content = contentGenerator.generateDomestic(title, journalName, publishDate,
                    authors, abstractText, keywords);
        } else {
            content = contentGenerator.generateInternational(title, cleanJournal, publishDate,
                    authors, abstractText, keywords, sourceUrl);
        }

        // Type 和 JournalAbbName（国际论文）
        String type = null;
        String abbName = null;
        if (!isChinese && !cleanJournal.isEmpty()) {
            type = journalMeta.getType(cleanJournal);
            abbName = journalMeta.getAbbName(cleanJournal);
        }

        return Paper.builder()
                .title(title)
                .journalName(journalName)
                .publishDate(publishDate)
                .releaseDate(today)                       // 转换日期
                .content(content)                          // 生成的 HTML
                .relateDBs("")                             // 空，等人工
                .paperId(paperId)
                .type(type)
                .journalAbbName(abbName)
                .authors(authors)                          // 保留在结构化字段
                .abstractText(abstractText)
                .keywords(keywords)
                .sourceUrl(sourceUrl)
                .excelRow(rowNum)
                .sourceFile(isChinese ? "DomesticPaper"
                        : journalMeta.isInternationalChinese(title, abstractText)
                                ? "InternationalChinesePaper_" + (type != null ? type : "Unknown")
                                : "Internationalpaper_" + (type != null ? type : "Unknown"))
                .build();
    }

    /**
     * 识别是否为国内论文（中文期刊）
     * 根据「分类」列内容判断：含《》→ 国内，不含 → 国际
     * 在首次调用时取第一行数据判断
     */
    private boolean isChineseFormat(Sheet sheet, Map<String, Integer> hdr) {
        // 取第一行数据的「分类」列
        for (int i = 1; i <= Math.min(sheet.getLastRowNum(), 5); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            String fenlei = getByHeader(row, hdr, "分类");
            if (fenlei != null) {
                // 含《》的是国内期刊（如《财经研究》），不含的是国际期刊（如 Accounting Review）
                return fenlei.contains("《") || fenlei.contains("》");
            }
        }
        // 兜底：看表头
        return !hdr.containsKey("doi");
    }

    // ==================== 去重 ====================

    /**
     * 判重上下文：一次导入内共用
     *
     * - existingByCollection：目标集合里已有的去重键，按集合懒加载一次，避免每行一次查询
     * - seen：本批次已接受的键，拦同一个文件内部的重复行（这批数据要循环结束才写库，
     *   光查库拦不住）
     */
    private static class DedupeContext {
        final Map<String, Set<String>> existingByCollection = new HashMap<>();
        final Set<String> seen = new HashSet<>();
    }

    /**
     * 已在库里或本批次已出现过 → true（并记一条 skipped）
     *
     * 判重键用 标题+期刊+发表日期+paperId 四项，与上传外网一致，见 {@link PaperDedupe}。
     * paperId 为空不再跳过判重：靠其余三项照样能拦住重复导入。
     */
    private boolean isDuplicate(Paper paper, String coll, DedupeContext ctx,
                                int rowNum, ImportResult result) {
        String key = PaperDedupe.keyOf(paper);

        if (!ctx.seen.add(key)) {
            result.addSkipped("第" + rowNum + "行 与本次导入的前序行重复: " + describe(paper));
            return true;
        }

        Set<String> existing = ctx.existingByCollection.computeIfAbsent(coll, this::loadExistingKeys);
        if (existing.contains(key)) {
            result.addSkipped("第" + rowNum + "行 " + coll + " 中已存在: " + describe(paper));
            return true;
        }
        return false;
    }

    /** 加载某集合已有的去重键，只投影判重用到的 4 个字段 */
    private Set<String> loadExistingKeys(String coll) {
        Set<String> keys = new HashSet<>();
        if (!mongoTemplate.collectionExists(coll)) return keys;

        // 不过滤已删除的：墓碑要参与判重。用户删掉的是"抓取得不对"的数据，
        // 下次导入同一份 Excel 不该又把它导进来（要恢复请去回收站）。
        org.springframework.data.mongodb.core.query.Query q =
                new org.springframework.data.mongodb.core.query.Query();
        q.fields().include(PaperDedupe.KEY_FIELDS);
        for (org.bson.Document doc : mongoTemplate.find(q, org.bson.Document.class, coll)) {
            keys.add(PaperDedupe.keyOf(doc));
        }
        log.info("  {}: 已有 {} 条参与判重", coll, keys.size());
        return keys;
    }

    /** skipped 提示用的简短描述，标题过长截断 */
    private String describe(Paper paper) {
        String title = paper.getTitle() == null ? "(无标题)" : paper.getTitle();
        if (title.length() > 40) title = title.substring(0, 40) + "...";
        String pid = paper.getPaperId();
        return pid == null || pid.isEmpty() ? title : title + " [PaperID=" + pid + "]";
    }

    /** 确定目标集合 */
    private String resolveCollection(boolean isChinese, String type, boolean isIntlChinese) {
        if (isChinese) return "domestic_papers";
        if ("Accounting".equalsIgnoreCase(type)) {
            return isIntlChinese ? "intl_chinese_accounting" : "intl_accounting";
        }
        if ("Finance".equalsIgnoreCase(type)) {
            return isIntlChinese ? "intl_chinese_finance" : "intl_finance";
        }
        return "intl_accounting"; // 默认可修改
    }

    // ==================== 批量目录导入 ====================

    public BatchImportResult importDirectory(String dirPath) {
        BatchImportResult batch = new BatchImportResult();
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            batch.getErrors().add("目录不存在: " + dirPath);
            return batch;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".xlsx") || name.endsWith(".xls"));
        if (files == null || files.length == 0) {
            batch.getErrors().add("目录下无 Excel 文件");
            return batch;
        }

        for (File file : files) {
            try {
                ImportResult r = importFromFile(file);
                batch.addResult(file.getName(), r);
            } catch (Exception e) {
                batch.getErrors().add(file.getName() + ": " + e.getMessage());
            }
        }
        return batch;
    }

    private ImportResult importFromFile(File file) throws Exception {
        String filename = file.getName();
        log.info("导入文件: {}", filename);
        ImportResult result = new ImportResult();

        try (InputStream is = new FileInputStream(file)) {
            Workbook workbook = filename.endsWith(".xls")
                    ? new HSSFWorkbook(is) : new XSSFWorkbook(is);

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> headerMap = buildHeaderMap(headerRow);
            boolean isChinese = isChineseFormat(sheet, headerMap);

            Map<String, List<Paper>> groupedPapers = new LinkedHashMap<>();
            String today = LocalDate.now().format(DATE_FMT);
            DedupeContext dedupe = new DedupeContext();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) continue;
                try {
                    Paper paper = convertRow(row, headerMap, isChinese, today, i + 1);
                    String coll = resolveCollection(isChinese, paper.getType(),
                            journalMeta.isInternationalChinese(paper.getTitle(), paper.getAbstractText()));
                    if (isDuplicate(paper, coll, dedupe, i + 1, result)) continue;
                    groupedPapers.computeIfAbsent(coll, k -> new ArrayList<>()).add(paper);
                } catch (Exception e) {
                    result.addError("第" + (i + 1) + "行: " + e.getMessage());
                }
            }

            int total = 0;
            List<String> colls = new ArrayList<>();
            for (Map.Entry<String, List<Paper>> e : groupedPapers.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    mongoTemplate.insert(e.getValue(), e.getKey());
                    total += e.getValue().size();
                    colls.add(e.getKey() + "(" + e.getValue().size() + "条)");
                }
            }
            result.setInserted(total);
            result.setCollection(String.join(", ", colls));
            workbook.close();
        }
        return result;
    }

    // ==================== 兼容旧版导入（有 Content 的 Excel） ====================

    /**
     * 兼容旧版导入：Excel 自带 Content 列，用 Jsoup 解析
     */
    public ImportResult importLegacy(MultipartFile file, String collectionName) {
        String filename = file.getOriginalFilename();
        String baseName = filename != null ? removeExtension(filename) : "unknown";
        String collName = collectionName != null ? collectionName
                : FILE_TO_COLLECTION.getOrDefault(baseName, baseName.toLowerCase().replaceAll("[^a-z0-9_]", "_"));

        ImportResult result = new ImportResult();
        result.setCollection(collName);

        try (InputStream is = file.getInputStream()) {
            Workbook workbook = filename != null && filename.endsWith(".xls")
                    ? new HSSFWorkbook(is) : new XSSFWorkbook(is);

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> headerMap = buildHeaderMap(headerRow);

            List<Paper> toInsert = new ArrayList<>();
            DedupeContext dedupe = new DedupeContext();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) continue;
                try {
                    Paper paper = legacyRowToPaper(row, headerMap, i + 1, baseName);
                    if (isDuplicate(paper, collName, dedupe, i + 1, result)) continue;
                    toInsert.add(paper);
                } catch (Exception e) {
                    result.addError("第" + (i + 1) + "行: " + e.getMessage());
                }
            }

            if (!toInsert.isEmpty()) {
                mongoTemplate.insert(toInsert, collName);
                result.setInserted(toInsert.size());
            }
            workbook.close();
        } catch (Exception e) {
            log.error("导入失败", e);
            result.getErrors().add("文件读取失败: " + e.getMessage());
        }
        return result;
    }

    private Paper legacyRowToPaper(Row row, Map<String, Integer> hdr, int rowNum, String sourceFile) {
        String contentHtml = getByHeader(row, hdr, "Content");
        String relateDBs = getByHeader(row, hdr, "RelateDBs");
        ParsedContent parsed = parseHtmlContent(contentHtml);

        return Paper.builder()
                .title(firstNonNull(parsed.title, getByHeader(row, hdr, "PaperTitle")))
                .journalName(getByHeader(row, hdr, "JournalName"))
                .publishDate(getByHeader(row, hdr, "PublishDate"))
                .releaseDate(getByHeader(row, hdr, "ReleaseDate"))
                .content(contentHtml)
                // 缺列或空单元格时 getByHeader 返回 null；relateDBs 只用 "" 表示"无关联"
                .relateDBs(relateDBs == null ? "" : relateDBs)
                .paperId(getByHeader(row, hdr, "PaperID"))
                .journalAbbName(getByHeader(row, hdr, "JournalAbbName"))
                .type(getByHeader(row, hdr, "Type"))
                .authors(parsed.authors)
                .abstractText(parsed.abstractText)
                .keywords(parsed.keywords)
                .sourceUrl(parsed.url)
                .excelRow(rowNum)
                .sourceFile(sourceFile)
                .build();
    }

    private ParsedContent parseHtmlContent(String html) {
        ParsedContent pc = new ParsedContent();
        if (html == null || html.isEmpty()) return pc;
        try {
            Document doc = Jsoup.parse(html);
            Element titleEl = doc.select(".papertitle").first();
            if (titleEl != null) pc.title = titleEl.text().trim();

            Elements authorEls = doc.select(".paperauthor");
            if (!authorEls.isEmpty()) {
                pc.authors = authorEls.stream()
                        .map(e -> e.text().trim()).filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("; "));
            } else {
                Element paperjournalEl = doc.select(".paperjournal").first();
                if (paperjournalEl != null) {
                    Element next = paperjournalEl.parent();
                    if (next != null) {
                        // 域内论文每位作者各占一个无 class 的 span（"彭韶兵；" "王玉；" "郑伟宏"），
                        // 早先这里取到第一个就 break，多作者被截成一个人。改为全部收集。
                        List<String> names = new ArrayList<>();
                        for (Element span : next.select("span")) {
                            String text = span.text().trim();
                            if (span.className().isEmpty() && !text.isEmpty()
                                    && text.length() > 2 && text.length() < 200
                                    && (text.contains(";") || text.contains("；")
                                        || text.matches("^[\\u4e00-\\u9fa5\\s;；]+$"))) {
                                String name = text.replaceAll("[；;]+$", "").trim();
                                if (!name.isEmpty()) names.add(name);
                            }
                        }
                        if (!names.isEmpty()) pc.authors = String.join("; ", names);
                    }
                }
            }

            Element absEl = doc.select(".paperabstract").first();
            if (absEl != null) pc.abstractText = absEl.text().trim();

            Element kwEl = doc.select(".paperkeywords").first();
            if (kwEl != null) {
                pc.keywords = kwEl.text().trim()
                        .replaceFirst("^(关键字[：:]\\s*|Keywords?:?\\s*)", "");
            }

            Element linkEl = doc.select("a.paperlink").first();
            if (linkEl != null) pc.url = linkEl.attr("href");
        } catch (Exception e) {
            log.warn("HTML 解析异常: {}", e.getMessage());
        }
        return pc;
    }

    private static class ParsedContent {
        String title, authors, abstractText, keywords, url;
    }

    // ==================== 工具方法 ====================

    private Map<String, Integer> buildHeaderMap(Row headerRow) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String header = getCellString(cell);
            if (header != null && !header.isEmpty()) map.put(header.trim(), i);
        }
        return map;
    }

    private String getByHeader(Row row, Map<String, Integer> hdr, String... candidates) {
        for (String c : candidates) {
            Integer idx = hdr.get(c);
            if (idx != null) return getCellString(row.getCell(idx));
        }
        return null;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                DecimalFormat df = new DecimalFormat("#.##");
                return df.format(cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue().trim(); }
                catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            default: return null;
        }
    }

    private boolean isRowEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String val = getCellString(cell);
                if (val != null && !val.isEmpty()) return false;
            }
        }
        return true;
    }

    private String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    // ==================== 结果类 ====================

    public static class ImportResult {
        private int inserted = 0;
        private List<String> skipped = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private String collection;

        public void addSkipped(String msg) { skipped.add(msg); }
        public void addError(String msg) { errors.add(msg); }
        public int getInserted() { return inserted; }
        public void setInserted(int inserted) { this.inserted = inserted; }
        public List<String> getSkipped() { return skipped; }
        public void setSkipped(List<String> skipped) { this.skipped = skipped; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
    }

    public static class BatchImportResult {
        private List<Map<String, Object>> results = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private int totalInserted = 0;

        public void addResult(String filename, ImportResult r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", filename);
            m.put("collection", r.getCollection());
            m.put("inserted", r.getInserted());
            m.put("skipped", r.getSkipped().size());
            m.put("errors", r.getErrors());
            results.add(m);
            totalInserted += r.getInserted();
        }
        public List<Map<String, Object>> getResults() { return results; }
        public List<String> getErrors() { return errors; }
        public int getTotalInserted() { return totalInserted; }
    }
}
