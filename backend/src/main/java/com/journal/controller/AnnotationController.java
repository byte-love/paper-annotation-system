package com.journal.controller;

import com.journal.model.DataHubDatabase;
import com.journal.model.Paper;
import com.journal.service.PaperFilters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 数据库标注 REST API
 *
 * 供"数据库标注"工作台使用：逐篇审核论文，勾选其用到的 DataHub 数据库，
 * 结果写入论文的 relateDBs 字段（ID 英文逗号分隔，与 DataHub 正式库格式一致）。
 */
@Slf4j
@RestController
@RequestMapping("/api/annotation")
public class AnnotationController {

    /** 五个论文集合（与业务分类一一对应），保存接口只允许操作这五个 */
    private static final List<String> PAPER_COLLECTIONS = List.of(
            "domestic_papers",
            "intl_chinese_accounting",
            "intl_chinese_finance",
            "intl_accounting",
            "intl_finance");

    /** 每篇论文最多关联的数据库数量（v2.1，前端 DbSelector 同步限制） */
    private static final int MAX_RELATE_DBS = 4;

    private final MongoTemplate mongoTemplate;

    public AnnotationController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 获取 DataHub 数据库目录，按板块分组（板块内按 DataHub 官方排序）
     * GET /api/annotation/databases
     */
    @GetMapping("/databases")
    public ResponseEntity<List<Map<String, Object>>> databases() {
        List<DataHubDatabase> all = mongoTemplate.findAll(DataHubDatabase.class);
        all.sort(Comparator.comparing(d -> d.getOrders() == null ? Integer.MAX_VALUE : d.getOrders()));

        Map<String, List<DataHubDatabase>> grouped = new LinkedHashMap<>();
        for (DataHubDatabase db : all) {
            grouped.computeIfAbsent(db.getPartition(), k -> new ArrayList<>()).add(db);
        }

        List<Map<String, Object>> resp = new ArrayList<>();
        grouped.forEach((partition, dbs) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partition", partition);
            m.put("databases", dbs);
            resp.add(m);
        });
        return ResponseEntity.ok(resp);
    }

    /**
     * 各集合待标注论文数量
     * GET /api/annotation/counts
     */
    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> counts() {
        Map<String, Long> resp = new LinkedHashMap<>();
        for (String collection : PAPER_COLLECTIONS) {
            resp.put(collection, mongoTemplate.count(new Query(pendingCriteria()), collection));
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询指定集合的全部待标注论文（relateDBs 为空且未确认无关联）
     * GET /api/annotation/pending?collection=domestic_papers
     */
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> pending(@RequestParam String collection) {
        if (!PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        List<Paper> papers = mongoTemplate.find(new Query(pendingCriteria()), Paper.class, collection);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", papers);
        resp.put("total", papers.size());
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    /**
     * 按 id 列表取论文（保持请求顺序返回，已被标注的也能取到）。
     * 用于恢复标注批次快照：批次内已标注的论文不在 pending 里，需按 id 回取。
     * POST /api/annotation/papersByIds
     * Body: { "collection": "domestic_papers", "ids": ["id1", "id2"] }
     */
    @PostMapping("/papersByIds")
    public ResponseEntity<Map<String, Object>> papersByIds(@RequestBody Map<String, Object> body) {
        String collection = (String) body.get("collection");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        if (!PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of("data", List.of(), "total", 0, "collection", collection));
        }
        // 排除已删除的：批次快照里的论文可能在别处被删掉了，恢复批次时不该再挂出来
        List<Paper> papers = mongoTemplate.find(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").in(ids),
                        PaperFilters.notDeleted())),
                Paper.class, collection);
        // 按请求的 id 顺序返回（批次内顺序必须稳定，进度和跳转都依赖下标）
        Map<String, Paper> byId = new HashMap<>();
        for (Paper p : papers) byId.put(p.getId(), p);
        List<Paper> ordered = new ArrayList<>();
        for (String id : ids) {
            Paper p = byId.get(id);
            if (p != null) ordered.add(p);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", ordered);
        resp.put("total", ordered.size());
        resp.put("collection", collection);
        return ResponseEntity.ok(resp);
    }

    /**
     * 保存单篇论文的标注结果
     * PUT /api/annotation/save
     * Body: { "collection": "domestic_papers", "id": "xxx",
     *         "relateDBs": "CPTD,DARD",   // 勾选的 ID，英文逗号分隔
     *         "noRelateDbs": false }       // true = 确认该论文无关联数据库
     */
    @PutMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        String collection = (String) body.get("collection");
        String id = (String) body.get("id");
        String relateDBs = body.get("relateDBs") == null ? "" : body.get("relateDBs").toString().trim();
        boolean noRelateDbs = Boolean.TRUE.equals(body.get("noRelateDbs"));

        if (!PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (id == null || id.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少论文 id"));
        }
        if (!noRelateDbs && relateDBs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未勾选任何数据库，也未确认无关联"));
        }
        // v2.1：每篇论文最多关联 4 个数据库
        if (!relateDBs.isEmpty() && relateDBs.split(",").length > MAX_RELATE_DBS) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "每篇论文最多关联 " + MAX_RELATE_DBS + " 个数据库"));
        }

        // 校验勾选的 ID 都在 DataHub 目录中，防止脏数据进入 relateDBs。
        // 目录为空时跳过校验：否则任何标注都存不进去（目录空了本身是故障，
        // 不该连带把标注功能锁死），日志留痕提醒去恢复目录。
        if (!relateDBs.isEmpty()) {
            Set<String> validIds = new HashSet<>();
            for (DataHubDatabase db : mongoTemplate.findAll(DataHubDatabase.class)) {
                validIds.add(db.getId());
            }
            if (validIds.isEmpty()) {
                log.warn("DataHub 数据库目录(datahub_databases)为空，已跳过 relateDBs 合法性校验，请尽快恢复目录数据");
            } else {
                for (String dbId : relateDBs.split(",")) {
                    if (!validIds.contains(dbId.trim())) {
                        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "无效的数据库 ID: " + dbId));
                    }
                }
            }
        }

        // v4.0：已推送过 150 的论文改了关联数据库，标记待重新上传
        // 带 notDeleted：删掉的论文不接受标注（标注页删完会跳下一篇，避免竞态下写回墓碑）
        Paper before = mongoTemplate.findOne(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").is(id),
                        PaperFilters.notDeleted())),
                Paper.class, collection);
        if (before == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未找到论文（或已被删除）: " + id));
        }
        String newRelateDBs = noRelateDbs ? "" : relateDBs;
        boolean changed = !Objects.equals(before.getRelateDBs() == null ? "" : before.getRelateDBs(), newRelateDBs)
                || !Objects.equals(Boolean.TRUE.equals(before.getNoRelateDbs()), noRelateDbs);
        boolean nowDirty = Boolean.TRUE.equals(before.getUploaded())
                && (changed || Boolean.TRUE.equals(before.getUploadDirty()));

        Update update = new Update()
                .set("relateDBs", newRelateDBs)
                .set("noRelateDbs", noRelateDbs)
                .set("reAnnotate", false)    // 标注完成，清除"重新标注"标记
                .set("uploadDirty", nowDirty);
        // 用 matchedCount 而非 modifiedCount：v2.1 下一篇=保存，返回上一篇后未修改
        // 直接翻页会重复保存相同内容（modified=0），不应视为失败
        long matched = mongoTemplate.updateFirst(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").is(id),
                        PaperFilters.notDeleted())),
                update, collection).getMatchedCount();

        if (matched == 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未找到论文（或已被删除）: " + id));
        }
        return ResponseEntity.ok(Map.of("success", true, "id", id, "relateDBs", relateDBs,
                "noRelateDbs", noRelateDbs, "uploadDirty", nowDirty));
    }

    /**
     * 批量送回"数据库标注"重新处理（保留原 relateDBs，标注页会预勾选供修改）
     * POST /api/annotation/reAnnotate
     * Body: { "collection": "domestic_papers", "ids": ["id1", "id2"] }
     */
    @PostMapping("/reAnnotate")
    public ResponseEntity<Map<String, Object>> reAnnotate(@RequestBody Map<String, Object> body) {
        String collection = (String) body.get("collection");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");

        if (!PAPER_COLLECTIONS.contains(collection)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "非法集合名: " + collection));
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未选择任何论文"));
        }

        // 已删除的不送回标注，否则墓碑会被 reAnnotate 拽进待办队列
        long modified = mongoTemplate.updateMulti(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").in(ids),
                        PaperFilters.notDeleted())),
                new Update().set("reAnnotate", true),
                collection).getModifiedCount();
        return ResponseEntity.ok(Map.of("success", true, "requested", ids.size(), "modified", modified));
    }

    /**
     * 待标注 = relateDBs 为空（null 或 ""）且未被人工确认"无关联"，或被用户送回重新标注。
     * 已删除的（墓碑）一律排除，否则删掉的论文还会排在标注队列里等着处理。
     */
    private Criteria pendingCriteria() {
        return new Criteria().andOperator(
                PaperFilters.notDeleted(),
                new Criteria().orOperator(
                        new Criteria().andOperator(
                                new Criteria().orOperator(
                                        Criteria.where("relateDBs").is(null),
                                        Criteria.where("relateDBs").is("")),
                                Criteria.where("noRelateDbs").ne(true)),
                        Criteria.where("reAnnotate").is(true)));
    }
}
