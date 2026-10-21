package com.journal.service;

import com.journal.model.Paper;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上传外网服务
 *
 * v4.0 起论文上传后不再从工作集合迁走：
 * 1. 上传范围 = 工作集合（小写）中"已标注或曾上传"的论文 + 归档集合（大写）里的历史遗留数据；
 *    两边按 paperId（缺失时用 标题|期刊|发表日期）去重，同一篇以工作集合为准。
 * 2. 排序后全量覆盖推送到目标 SQL Server。
 * 3. 上传成功后只给工作集合打 uploaded 标记，论文仍留在论文列表里，
 *    用户可随时送回「数据库标注」修改关联数据库，改动后标记 uploadDirty，下次上传带上最新值。
 *
 * v4.1 软删除：被用户删掉的论文（deleted=true）不参与推送，且用它的去重键
 * 拦掉归档集合里的同一篇——否则归档副本会把删除操作在 150 上抹平。
 * 因为推送是全量覆盖，删除要等下一次上传才会同步到 150。
 */
@Slf4j
@Service
public class ExternalUploadService {

    private final MongoTemplate mongoTemplate;

    /** 5对小写/大写集合映射 */
    private static final List<CollectionPair> PAIRS = List.of(
            new CollectionPair("domestic_papers", "DomesticPaper"),
            new CollectionPair("intl_chinese_accounting", "InternationalChinesePaper_Accounting"),
            new CollectionPair("intl_chinese_finance", "InternationalChinesePaper_Finance"),
            new CollectionPair("intl_accounting", "Internationalpaper_Accounting"),
            new CollectionPair("intl_finance", "Internationalpaper_Finance")
    );

    /** 目标数据库连接（从 application.yml 读取，程哥改正式库只改配置文件即可） */
    @Value("${external.sqlserver.host}")
    private String sqlHost;

    @Value("${external.sqlserver.port}")
    private int sqlPort;

    @Value("${external.sqlserver.user}")
    private String sqlUser;

    @Value("${external.sqlserver.password}")
    private String sqlPassword;

    @Value("${external.sqlserver.database}")
    private String sqlDatabase;

    public ExternalUploadService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ==================== 150 状态查询 ====================

    /** 查看 数据库中各表的数据情况 */
    @SuppressWarnings("CallToDriverManagerGetConnection")
    public Map<String, Object> get150Status() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(getDbUrl(), sqlUser, sqlPassword);
             Statement stmt = conn.createStatement()) {
            for (CollectionPair pair : PAIRS) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("table", pair.large);
                try {
                    // 行数
                    ResultSet rs = stmt.executeQuery(
                            String.format("SELECT COUNT(*) FROM [%s]", pair.large));
                    rs.next();
                    int count = rs.getInt(1);
                    rs.close();
                    t.put("count", count);

                    // 前 3 条样例（按 ID 排序）
                    if (count > 0) {
                        List<Map<String, Object>> samples = new ArrayList<>();
                        rs = stmt.executeQuery(String.format(
                                "SELECT TOP 3 ID, PaperTitle, PublishDate, RelateDBs FROM [%s] ORDER BY ID", pair.large));
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("ID", rs.getInt("ID"));
                            row.put("PaperTitle", rs.getString("PaperTitle"));
                            row.put("PublishDate", rs.getString("PublishDate"));
                            row.put("RelateDBs", rs.getString("RelateDBs"));
                            samples.add(row);
                        }
                        rs.close();
                        t.put("samples", samples);
                    }
                } catch (Exception e) {
                    t.put("count", 0);
                    t.put("error", e.getMessage());
                }
                tables.add(t);
            }
            result.put("tables", tables);
            result.put("success", true);
        } catch (Exception e) {
            log.error("查询 150 状态失败", e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 预览 ====================

    /**
     * 预览本次待推送的论文：已标注但从未上传，或上传后又改过关联数据库
     */
    public Map<String, Object> preview() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        int totalNew = 0;
        int totalUploaded = 0;
        int totalDeletedPending = 0;

        for (CollectionPair pair : PAIRS) {
            List<Paper> pending = findPendingUpload(pair.small);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("collection", pair.small);
            summary.put("label", collectionLabel(pair.small));
            summary.put("table150", pair.large);
            summary.put("newCount", pending.size());
            // 已上传过的数量，让用户知道本次全量推送的规模（不含已删除的）
            long uploadedCount = mongoTemplate.count(
                    new Query(new Criteria().andOperator(
                            PaperFilters.notDeleted(),
                            Criteria.where("uploaded").is(true))), pair.small);
            summary.put("uploadedCount", uploadedCount);
            totalUploaded += (int) uploadedCount;
            // 已删除但 150 上还留着的：本次全量覆盖会把它们抹掉
            long deletedPending = mongoTemplate.count(
                    new Query(new Criteria().andOperator(
                            PaperFilters.deleted(),
                            Criteria.where("uploaded").is(true))), pair.small);
            summary.put("deletedPending", deletedPending);
            totalDeletedPending += (int) deletedPending;
            // 取前 5 条作为预览样例
            List<Map<String, String>> samples = pending.stream().limit(5).map(p -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("title", p.getTitle());
                m.put("journalName", p.getJournalName());
                m.put("publishDate", p.getPublishDate());
                m.put("relateDBs", nullToEmpty(p.getRelateDBs()));
                m.put("paperId", p.getPaperId());
                m.put("dirty", Boolean.TRUE.equals(p.getUploadDirty()) ? "1" : "");
                return m;
            }).collect(Collectors.toList());
            summary.put("samples", samples);
            summaries.add(summary);
            totalNew += pending.size();
        }

        result.put("summaries", summaries);
        result.put("totalNew", totalNew);
        result.put("totalUploaded", totalUploaded);
        result.put("totalDeletedPending", totalDeletedPending);
        return result;
    }

    // ==================== 执行上传 ====================

    /**
     * 完整上传流程
     */
    @SuppressWarnings("CallToDriverManagerGetConnection")
    public Map<String, Object> execute() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> perCollection = new ArrayList<>();
        int totalUploaded = 0;

        // 1. 确保 SQL Server 库和表存在
        try {
            ensureDatabaseAndTables();
        } catch (Exception e) {
            log.error("初始化 SQL Server 库/表失败", e);
            result.put("success", false);
            result.put("message", "无法连接外网数据库: " + e.getMessage());
            return result;
        }

        // 2. 逐对处理
        for (CollectionPair pair : PAIRS) {
            Map<String, Object> colResult = processPair(pair);
            perCollection.add(colResult);
            if (Boolean.TRUE.equals(colResult.get("success"))) {
                totalUploaded += (int) colResult.get("uploaded");
            }
        }

        result.put("success", true);
        result.put("perCollection", perCollection);
        result.put("totalUploaded", totalUploaded);
        return result;
    }

    /** 处理一对集合：合并→排序→上传150→迁移MongoDB */
    private Map<String, Object> processPair(CollectionPair pair) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("small", pair.small);
        r.put("large", pair.large);
        r.put("table150", pair.large);

        try {
            // 归档集合（大写）：v3.0 遗留的历史数据，只读兜底
            List<Paper> archiveDocs = findAllFromArchive(pair.large);
            // 工作集合（小写）：已标注或曾上传过的论文，本次全量推送
            List<Paper> workPapers = findUploadable(pair.small);
            // 用户删掉的，用来拦住归档里的同一篇
            Set<String> deletedKeys = findDeletedKeys(pair.small);
            // 本次真正新增/变更的部分，仅用于回显
            int pendingCount = findPendingUpload(pair.small).size();

            if (archiveDocs.isEmpty() && workPapers.isEmpty()) {
                r.put("success", true);
                r.put("uploaded", 0);
                r.put("oldCount", 0);
                r.put("newCount", 0);
                r.put("pendingCount", 0);
                return r;
            }

            // 合并（去重）+ 排序 + 分配 ID
            List<Map<String, Object>> merged = mergeAndSort(archiveDocs, workPapers, deletedKeys);
            r.put("oldCount", archiveDocs.size());
            r.put("newCount", workPapers.size());
            r.put("pendingCount", pendingCount);
            r.put("deletedCount", deletedKeys.size());
            r.put("uploaded", merged.size());

            // 上传到 150
            uploadToSqlServer(pair.large, merged);

            // 打上传标记（论文留在工作集合，不迁移、不删除）
            markUploaded(pair.small, workPapers);

            r.put("success", true);
            log.info("{}: 归档{} + 工作{}（其中本次新增/变更{}）- 已删除{} → 去重合并{} → 150:{}",
                    pair.small, archiveDocs.size(), workPapers.size(), pendingCount,
                    deletedKeys.size(), merged.size(), pair.large);
        } catch (Exception e) {
            log.error("处理 {} 失败", pair.small, e);
            r.put("success", false);
            r.put("message", e.getMessage());
        }
        return r;
    }

    // ==================== MongoDB 读取 ====================

    /** 已标注：relateDBs 非空 OR 人工确认无关联 */
    private static Criteria annotatedCriteria() {
        return new Criteria().orOperator(
                Criteria.where("relateDBs").ne(null).ne(""),
                Criteria.where("noRelateDbs").is(true));
    }

    /**
     * 本次推送范围：已标注的 + 曾上传过的。
     * 后者不能漏——历史遗留数据里有 relateDBs 为空的论文，
     * 若只按"已标注"筛，全量覆盖会把它们从 150 上抹掉。
     */
    private List<Paper> findUploadable(String collection) {
        Criteria c = new Criteria().andOperator(
                PaperFilters.notDeleted(),
                new Criteria().orOperator(
                        annotatedCriteria(),
                        Criteria.where("uploaded").is(true)));
        return mongoTemplate.find(new Query(c), Paper.class, collection);
    }

    /** 待推送：已标注但从未上传，或上传后关联数据库被改过（uploadDirty） */
    private List<Paper> findPendingUpload(String collection) {
        Criteria c = new Criteria().andOperator(
                PaperFilters.notDeleted(),
                annotatedCriteria(),
                new Criteria().orOperator(
                        Criteria.where("uploaded").ne(true),
                        Criteria.where("uploadDirty").is(true)));
        return mongoTemplate.find(new Query(c), Paper.class, collection);
    }

    /**
     * 已删除论文的去重键（墓碑）。
     *
     * 归档集合（大写）是只读兜底，删除时不动它，所以工作集合删掉一篇之后，
     * 归档里的同一篇会在 {@link #mergeAndSort} 里被当成"工作集合缺失的历史数据"补回去，
     * 下次上传又出现在 150 上。用墓碑的去重键把它拦掉。
     */
    private Set<String> findDeletedKeys(String collection) {
        return mongoTemplate.find(new Query(PaperFilters.deleted()), Paper.class, collection)
                .stream().map(ExternalUploadService::dedupeKey).collect(Collectors.toSet());
    }

    /**
     * 从归档集合（大写）读取历史遗留论文。
     * 归档集合的字段是 camelCase（与工作集合一致，只有集合名是大写），
     * 所以直接按 Paper 读；PascalCase 只出现在 150 的 SQL Server 表里。
     */
    private List<Paper> findAllFromArchive(String collection) {
        return mongoTemplate.findAll(Paper.class, collection);
    }

    // ==================== 合并 & 排序 ====================

    /**
     * 合并归档论文和工作集合论文，去重后排序、分配自增 ID，字段转成 150 表的 PascalCase。
     * 同一篇论文两边都有时以工作集合为准（那边的关联数据库是用户最新改过的）。
     */
    // package-private + static：纯函数，不碰 MongoDB，便于单元测试（见 ExternalUploadMergeTest）
    static List<Map<String, Object>> mergeAndSort(List<Paper> archivePapers, List<Paper> workPapers,
                                                  Set<String> deletedKeys) {
        List<Map<String, Object>> all = new ArrayList<>();

        // 工作集合的论文全部保留（每条都是独立文档，同源不去重，避免误删）
        Set<String> workKeys = new HashSet<>();
        for (Paper p : workPapers) {
            workKeys.add(dedupeKey(p));
            all.add(toSqlRow(p));
        }

        // 归档数据只补工作集合里没有的那部分：回补前当兜底，回补后自然全部跳过。
        // 被用户删掉的（墓碑）也要跳过，否则归档副本会让删除在 150 上失效。
        for (Paper p : archivePapers) {
            String key = dedupeKey(p);
            if (!workKeys.contains(key) && !deletedKeys.contains(key)) {
                all.add(toSqlRow(p));
            }
        }

        // 排序：publishDate 降序，有 relateDBs 排前面
        all.sort((a, b) -> {
            String pa = (String) a.get("PublishDate");
            String pb = (String) b.get("PublishDate");
            Integer yma = parsePublishYm(pa);
            Integer ymb = parsePublishYm(pb);
            // 降序：大的在前；解析失败的排末尾
            if (yma != null && ymb != null && !yma.equals(ymb)) return ymb - yma;
            if (yma != null && ymb == null) return -1;
            if (yma == null && ymb != null) return 1;
            // 有关联数据库的排前面
            boolean ha = hasRelateDBs((String) a.get("RelateDBs"));
            boolean hb = hasRelateDBs((String) b.get("RelateDBs"));
            if (ha && !hb) return -1;
            if (!ha && hb) return 1;
            return 0;
        });

        // 分配自增 ID
        for (int i = 0; i < all.size(); i++) {
            all.get(i).put("ID", i + 1);
        }

        return all;
    }

    // ==================== SQL Server 操作 ====================

    /** 确保数据库和 5 张表存在（jTDS 兼容） */
    private void ensureDatabaseAndTables() throws Exception {
        // 连 master 建库
        try (Connection conn = DriverManager.getConnection(getMasterUrl(), sqlUser, sqlPassword);
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                    "IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = N'%s') CREATE DATABASE [%s]",
                    sqlDatabase, sqlDatabase));
        }

        // 连目标库，确保 5 张表存在
        try (Connection conn = DriverManager.getConnection(getDbUrl(), sqlUser, sqlPassword);
             Statement stmt = conn.createStatement()) {
            for (CollectionPair pair : PAIRS) {
                ResultSet rs = stmt.executeQuery(String.format(
                        "SELECT COUNT(*) FROM sysobjects WHERE name='%s' AND xtype='U'", pair.large));
                rs.next();
                if (rs.getInt(1) == 0) {
                    stmt.execute(String.format(
                            "CREATE TABLE [%s] ("
                            + "ID INT PRIMARY KEY, "
                            + "PaperTitle NVARCHAR(1000), "
                            + "JournalName NVARCHAR(500), "
                            + "PublishDate NVARCHAR(200), "
                            + "ReleaseDate NVARCHAR(50), "
                            + "Content TEXT, "
                            + "RelateDBs NVARCHAR(500), "
                            + "PaperID NVARCHAR(500))", pair.large));
                    log.info("创建表: {}", pair.large);
                }
                rs.close();
            }
        }
    }

    /** 上传数据到 150：备份现表 → 写入新数据 */
    private void uploadToSqlServer(String tableName, List<Map<String, Object>> rows) throws Exception {
        String backupTable = tableName + "00";
        String dbUrl = getDbUrl();

        try (Connection conn = DriverManager.getConnection(getDbUrl(), sqlUser, sqlPassword)) {
            conn.setAutoCommit(false);
            try {
                // 1. 删除旧的备份表（如果存在）
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(String.format(
                            "IF OBJECT_ID(N'%s', N'U') IS NOT NULL DROP TABLE [%s]", backupTable, backupTable));
                }

                // 2. 如果现表存在且有数据 → rename 为备份表
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery(String.format(
                            "SELECT COUNT(*) FROM sysobjects WHERE name='%s' AND xtype='U'", tableName));
                    rs.next();
                    boolean exists = rs.getInt(1) > 0;
                    rs.close();

                    if (exists) {
                        stmt.execute(String.format(
                                "EXEC sp_rename '%s', '%s'", tableName, backupTable));
                        log.info("备份: {} → {}", tableName, backupTable);
                    }
                }

                // 3. 创建新表
                String createTable = "CREATE TABLE [%s] ("
                        + "ID INT PRIMARY KEY, "
                        + "PaperTitle NVARCHAR(1000), "
                        + "JournalName NVARCHAR(500), "
                        + "PublishDate NVARCHAR(200), "
                        + "ReleaseDate NVARCHAR(50), "
                        + "Content TEXT, "
                        + "RelateDBs NVARCHAR(500), "
                        + "PaperID NVARCHAR(500)"
                        + ")";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(String.format(createTable, tableName));
                }

                // 4. 批量插入
                String insertSql = String.format(
                        "INSERT INTO [%s] (ID, PaperTitle, JournalName, PublishDate, ReleaseDate, Content, RelateDBs, PaperID) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", tableName);

                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    int batch = 0;
                    for (Map<String, Object> row : rows) {
                        ps.setInt(1, (int) row.get("ID"));
                        ps.setString(2, nvarchar((String) row.get("PaperTitle"), 1000));
                        ps.setString(3, nvarchar((String) row.get("JournalName"), 500));
                        ps.setString(4, nvarchar((String) row.get("PublishDate"), 200));
                        ps.setString(5, nvarchar((String) row.get("ReleaseDate"), 50));
                        ps.setString(6, (String) row.get("Content"));
                        ps.setString(7, nvarchar((String) row.get("RelateDBs"), 500));
                        ps.setString(8, nvarchar((String) row.get("PaperID"), 500));
                        ps.addBatch();
                        if (++batch % 200 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }

                conn.commit();
                log.info("上传完成: {} ← {} 行", tableName, rows.size());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ==================== 历史数据回补 ====================

    /**
     * 把 v3.0 迁移走的归档数据（大写集合）回补到工作集合（小写），字段 PascalCase → camelCase，
     * 并标记 uploaded=true。回补后这些论文重新出现在论文列表里，可送回标注修改关联数据库。
     *
     * 幂等：按去重键跳过工作集合里已存在的论文，重复执行不会产生副本。
     * 归档集合保持原样不动（只读兜底）。
     *
     * @param dryRun true = 只统计不写入
     */
    public Map<String, Object> backfillFromArchive(boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        int totalInserted = 0;
        int totalSkipped = 0;
        long totalRepaired = 0;

        for (CollectionPair pair : PAIRS) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("collection", pair.small);
            d.put("label", collectionLabel(pair.small));
            d.put("archive", pair.large);
            try {
                List<Paper> archivePapers = findAllFromArchive(pair.large);
                // 工作集合已有的去重键，避免回补出副本。
                // 故意包含已删除的（墓碑）：用户删掉的历史论文不该被回补重新拉回来。
                Set<String> existing = new HashSet<>();
                for (Paper p : mongoTemplate.findAll(Paper.class, pair.small)) {
                    existing.add(dedupeKey(p));
                }

                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                List<Document> toInsert = new ArrayList<>();
                int skipped = 0;
                // 只跳过工作集合里已有的；归档内部本来就重复的行原样搬过来，
                // 不在这里做数据清理，保证回补前后 150 上的数据量不变。
                // 幂等性由"跳过工作集合已有"保证：再跑一次全部命中跳过。
                for (Paper p : archivePapers) {
                    if (existing.contains(dedupeKey(p))) {
                        skipped++;
                        continue;
                    }
                    toInsert.add(toWorkingDoc(p, now));
                }

                d.put("archiveCount", archivePapers.size());
                d.put("willInsert", toInsert.size());
                d.put("skipped", skipped);

                if (!dryRun && !toInsert.isEmpty()) {
                    mongoTemplate.insert(toInsert, pair.small);
                    log.info("回补 {} ← {}：插入 {} 条，跳过 {} 条",
                            pair.small, pair.large, toInsert.size(), skipped);
                }

                // 修正已上传论文的"无关联"标记：已推送过 150 的都是人工审核过的，
                // relateDBs 为空就是确认无关联，不该躺在「数据库标注」待办里。
                // 早期回补把它们写成了 noRelateDbs=false，这里一并纠正。
                Query needFix = new Query(new Criteria().andOperator(
                        PaperFilters.notDeleted(),
                        Criteria.where("uploaded").is(true),
                        new Criteria().orOperator(
                                Criteria.where("relateDBs").is(null),
                                Criteria.where("relateDBs").is("")),
                        Criteria.where("noRelateDbs").ne(true)));
                long repaired = mongoTemplate.count(needFix, pair.small);
                if (!dryRun && repaired > 0) {
                    repaired = mongoTemplate.updateMulti(needFix,
                            new Update().set("noRelateDbs", true).set("reAnnotate", false),
                            pair.small).getModifiedCount();
                    log.info("修正 {} 的无关联标记 {} 条", pair.small, repaired);
                }
                d.put("repaired", repaired);

                d.put("success", true);
                totalInserted += toInsert.size();
                totalSkipped += skipped;
                totalRepaired += repaired;
            } catch (Exception e) {
                log.error("回补 {} 失败", pair.small, e);
                d.put("success", false);
                d.put("message", e.getMessage());
            }
            details.add(d);
        }

        result.put("success", true);
        result.put("dryRun", dryRun);
        result.put("totalInserted", totalInserted);
        result.put("totalSkipped", totalSkipped);
        result.put("totalRepaired", totalRepaired);
        result.put("details", details);
        return result;
    }

    /** 归档论文 → 工作集合文档，带已上传标记（_id 不带过去，工作集合生成新的） */
    private static Document toWorkingDoc(Paper p, String uploadedAt) {
        // 归档里 relateDBs 尾部常带逗号（如 "NFSD,CSEO,"），标注页按逗号分割会多出空项。
        // 归档里也有 null，统一归成空字符串——relateDBs 只用 "" 表示"无关联"。
        String relateDBs = nullToEmpty(p.getRelateDBs()).replaceAll(",+$", "").trim();

        Document d = new Document();
        d.put("title", p.getTitle());
        d.put("journalName", p.getJournalName());
        d.put("publishDate", p.getPublishDate());
        d.put("releaseDate", p.getReleaseDate());
        d.put("content", p.getContent());
        d.put("relateDBs", relateDBs);
        d.put("paperId", p.getPaperId());
        d.put("journalAbbName", p.getJournalAbbName());
        d.put("type", p.getType());
        d.put("authors", p.getAuthors());
        d.put("abstractText", p.getAbstractText());
        d.put("keywords", p.getKeywords());
        d.put("sourceUrl", p.getSourceUrl());
        d.put("sourceFile", p.getSourceFile());
        // 归档数据都已推送过 150
        d.put("uploaded", true);
        d.put("uploadedAt", uploadedAt);
        d.put("uploadDirty", false);
        d.put("reAnnotate", false);
        // 归档数据都是人工审核过的：relateDBs 为空 = 确认无关联数据库，
        // 不是"还没标注"。否则回补后会涌进「数据库标注」待办队列。
        d.put("noRelateDbs", relateDBs.isEmpty());
        return d;
    }

    // ==================== 上传标记 ====================

    /**
     * 给本次推送的论文打上传标记。论文继续留在工作集合里，
     * 列表仍能看到，用户随时可以送回标注修改关联数据库。
     */
    private void markUploaded(String collection, List<Paper> papers) {
        if (papers.isEmpty()) return;
        Set<String> ids = papers.stream().map(Paper::getId).collect(Collectors.toSet());
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        long modified = mongoTemplate.updateMulti(
                new Query(Criteria.where("_id").in(ids)),
                new Update()
                        .set("uploaded", true)
                        .set("uploadedAt", now)
                        .set("uploadDirty", false),
                collection).getModifiedCount();
        log.info("{} 标记已上传 {} 条（请求 {} 条）", collection, modified, ids.size());
    }

    // ==================== 工具方法 ====================

    private String getMasterUrl() {
        return String.format("jdbc:jtds:sqlserver://%s:%d/master;ssl=off", sqlHost, sqlPort);
    }

    private String getDbUrl() {
        return String.format("jdbc:jtds:sqlserver://%s:%d/%s;ssl=off",
                sqlHost, sqlPort, sqlDatabase);
    }

    /** 截断到指定长度（NVARCHAR 不需要像 MySQL 那样严格截断，但保险起见） */
    private static String nvarchar(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static boolean hasRelateDBs(String relateDBs) {
        return relateDBs != null && !relateDBs.isEmpty();
    }

    /** 论文 → 150 表的一行（camelCase → PascalCase） */
    private static Map<String, Object> toSqlRow(Paper p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PaperTitle", p.getTitle());
        row.put("JournalName", p.getJournalName());
        row.put("PublishDate", p.getPublishDate());
        row.put("ReleaseDate", p.getReleaseDate());
        row.put("Content", p.getContent());
        row.put("RelateDBs", nullToEmpty(p.getRelateDBs()));
        row.put("PaperID", p.getPaperId());
        return row;
    }

    /** 去重键，与 Excel 导入共用同一套标准，判重字段与理由见 {@link PaperDedupe} */
    private static String dedupeKey(Paper p) {
        return PaperDedupe.keyOf(p);
    }

    private static String dedupeKey(String paperId, String title, String journalName, String publishDate) {
        return PaperDedupe.keyOf(paperId, title, journalName, publishDate);
    }

    /**
     * RelateDBs 不允许为 null——没有关联库时写空字符串。
     * 150 表那一列是 NVARCHAR(500) 可空，但下游按"空字符串 = 无关联"读，
     * 混进 null 会让消费方多一种要判的情况。
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String collectionLabel(String name) {
        switch (name) {
            case "domestic_papers": return "国内论文";
            case "intl_chinese_accounting": return "国际中文-会计";
            case "intl_chinese_finance": return "国际中文-金融";
            case "intl_accounting": return "国际-会计";
            case "intl_finance": return "国际-金融";
            default: return name;
        }
    }

    // ==================== 日期解析（复用 PaperController 逻辑） ====================

    private static final String[] EN_MONTHS = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};
    private static final String[] EN_MONTHS_ABBR = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};

    private static Integer parsePublishYm(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher cn = Pattern.compile("(\\d{4})年(?:第)?(\\d{1,2})").matcher(s);
        if (cn.find()) {
            int month = Integer.parseInt(cn.group(2));
            return Integer.parseInt(cn.group(1)) * 100 + Math.min(Math.max(month, 1), 12);
        }
        String lower = s.toLowerCase();
        Integer year = null;
        Matcher y4 = Pattern.compile("(\\d{4})").matcher(s);
        if (y4.find()) {
            year = Integer.parseInt(y4.group(1));
        } else {
            Matcher y2 = Pattern.compile("[-\\s](\\d{2})\\s*$").matcher(s);
            if (y2.find()) year = 2000 + Integer.parseInt(y2.group(1));
        }
        if (year == null) return null;
        for (int i = 0; i < EN_MONTHS.length; i++) {
            if (lower.contains(EN_MONTHS[i])) return year * 100 + (i + 1);
        }
        for (int i = 0; i < EN_MONTHS_ABBR.length; i++) {
            if (lower.contains(EN_MONTHS_ABBR[i])) return year * 100 + (i + 1);
        }
        return year * 100 + 1;
    }

    // ==================== 内部类 ====================

    private static class CollectionPair {
        final String small;
        final String large;

        CollectionPair(String small, String large) {
            this.small = small;
            this.large = large;
        }
    }
}
