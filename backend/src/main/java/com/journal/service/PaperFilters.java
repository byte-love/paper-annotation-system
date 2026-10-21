package com.journal.service;

import com.journal.model.Paper;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * 软删除过滤 — 全项目共用一处定义
 *
 * 删除是打墓碑（deleted=true）而不是物理删除，所以每个"面向用户"的查询都得排除墓碑：
 * 论文列表、搜索、导出、标注待办、上传外网范围。
 * 唯独两处故意不过滤：
 * - Excel 导入判重（{@link ExcelImportService}）：墓碑要参与判重，删过的不能又导进来；
 * - 回收站查询：只看墓碑。
 */
public final class PaperFilters {

    private PaperFilters() {
    }

    /** 未删除：deleted 不为 true（老数据没这个字段，null 也算未删除） */
    public static Criteria notDeleted() {
        return Criteria.where("deleted").ne(true);
    }

    /** 已删除：deleted=true */
    public static Criteria deleted() {
        return Criteria.where("deleted").is(true);
    }

    /** 内存中判断，供已取出的论文列表过滤用 */
    public static boolean isDeleted(Paper p) {
        return Boolean.TRUE.equals(p.getDeleted());
    }
}
