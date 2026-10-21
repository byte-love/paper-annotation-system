package com.journal.service;

import com.journal.model.Paper;
import org.bson.Document;

/**
 * 论文去重键 — 导入与上传外网共用一套判重标准
 *
 * 去重键 = 标题 + 期刊 + 发表日期 + paperId 四者合起来，全一致才算同一篇。
 *
 * 为什么不能只用 paperId：
 * 国际库的 paperId 取自上游的 _id / doi / PaperID 列，实际装的可能是 DOI、
 * 论文链接、甚至期刊栏目页链接或 "N/A" 占位值。历史数据里存在一个 paperId
 * 对应十几篇不同论文的情况，只按它判重会把不同论文当成重复丢掉。
 *
 * 反过来 paperId 也不能省：同名同期同月的不同论文靠它区分。
 */
public final class PaperDedupe {

    /** 字段分隔符，用不可见控制字符，避免 "ab"+"c" 与 "a"+"bc" 撞键 */
    private static final char SEP = '\001';

    /** 构建去重键时用到的字段，供投影查询复用 */
    public static final String[] KEY_FIELDS = {"title", "journalName", "publishDate", "paperId"};

    private PaperDedupe() {
    }

    public static String keyOf(Paper p) {
        return keyOf(p.getPaperId(), p.getTitle(), p.getJournalName(), p.getPublishDate());
    }

    /** 从投影查询返回的 Document 建键，字段缺失按空串处理 */
    public static String keyOf(Document doc) {
        return keyOf(doc.getString("paperId"), doc.getString("title"),
                doc.getString("journalName"), doc.getString("publishDate"));
    }

    public static String keyOf(String paperId, String title, String journalName, String publishDate) {
        return norm(title) + SEP + norm(journalName) + SEP
                + norm(publishDate) + SEP + norm(paperId);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
