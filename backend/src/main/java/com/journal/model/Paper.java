package com.journal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

/**
 * 论文实体 — MongoDB 文档（工作集合，camelCase 字段）
 *
 * 数据架构（v4.0 起）：
 * - 工作集合（小写，如 domestic_papers）：全部论文常驻此处，用户标注编辑。
 *   上传外网后不再迁走，只打 uploaded 标记，随时可送回标注修改关联数据库。
 * - 归档集合（大写，如 DomesticPaper）：v3.0 历史遗留数据，只读兜底，不再写入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Paper {

    @Id
    private String id;

    // ===== 核心字段 =====

    @Indexed
    private String title;

    private String journalName;

    private String publishDate;

    private String releaseDate;

    private String content;

    private String relateDBs;

    private Boolean noRelateDbs;

    private Boolean reAnnotate;

    // ===== 上传外网状态（v4.0）=====

    /** true = 该论文已推送到外网 150；上传后论文仍留在工作集合，不再物理迁移 */
    private Boolean uploaded;

    /** 最近一次上传时间 yyyy-MM-dd HH:mm:ss */
    private String uploadedAt;

    /** true = 已上传过，但之后关联数据库被改动，需要重新推送 */
    private Boolean uploadDirty;

    // ===== 软删除（v4.1）=====

    /**
     * true = 已删除（墓碑）。抓取到不合要求的数据时由用户在论文列表/标注页删掉。
     *
     * 软删除而非物理删除的原因：
     * 1. 误删可恢复（回收站里点"恢复"把标记清掉即可）；
     * 2. 归档集合（大写）只读不动，靠墓碑的去重键在上传外网合并时拦住归档副本，
     *    否则回补过来的历史论文删掉后会被归档数据重新补上去；
     * 3. Excel 重新导入时墓碑参与判重，删过的论文不会又被导进来。
     */
    private Boolean deleted;

    /** 删除时间 yyyy-MM-dd HH:mm:ss */
    private String deletedAt;

    @Indexed
    private String paperId;

    private String journalAbbName;

    private String type;

    // ===== 辅助字段 =====

    private String authors;

    private String abstractText;

    private String keywords;

    private String sourceUrl;

    private Integer excelRow;

    private String sourceFile;

    @org.springframework.data.annotation.Transient
    private String collection;
}
