package com.journal.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * DataHub 数据库目录 — MongoDB 文档
 *
 * 数据来源：DataHub 平台 cas.SystemList 表快照（同步脚本导入），
 * 论文的 RelateDBs 字段存的即本表的 ID（英文逗号分隔，如 "CPTD,DARD"）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "datahub_databases")
public class DataHubDatabase {

    /** DataHub 数据库 ID，如 CPTD、CIRD */
    @Id
    private String id;

    /** 所属板块，如 "上市公司文本信息" */
    @Field("partition")
    private String partition;

    /** 数据库中文名，如 "专利文本" */
    private String name;

    /** DataHub 平台上的排序号 */
    private Integer orders;
}
