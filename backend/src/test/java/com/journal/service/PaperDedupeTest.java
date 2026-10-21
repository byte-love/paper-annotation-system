package com.journal.service;

import com.journal.model.Paper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 去重键行为测试
 *
 * 重点验证两个方向都不出错：
 * - 真重复要判成重复（重复导入同一个文件）
 * - 假重复不能判成重复（多篇论文共享一个 paperId 时不许合掉）
 */
class PaperDedupeTest {

    private Paper paper(String paperId, String title, String journal, String publishDate) {
        return Paper.builder()
                .paperId(paperId).title(title).journalName(journal).publishDate(publishDate)
                .build();
    }

    @Test
    @DisplayName("四项全一致 → 同一篇")
    void sameAllFields() {
        Paper a = paper("10.1016/j.jacceco.2019.101234", "盈余管理研究", "《会计研究》", "2019-06");
        Paper b = paper("10.1016/j.jacceco.2019.101234", "盈余管理研究", "《会计研究》", "2019-06");
        assertEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("共享同一个 paperId 的不同论文 → 不同篇（改动的核心目的）")
    void samePaperIdDifferentPapers() {
        String sharedId = "https://www.journals.example.com/toc/tar/2019/94/3";
        Paper a = paper(sharedId, "Audit Quality and Fees", "The Accounting Review", "2019-05");
        Paper b = paper(sharedId, "Tax Avoidance and Disclosure", "The Accounting Review", "2019-05");
        assertNotEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("paperId 为空也能靠其余三项判重")
    void emptyPaperIdStillDedupes() {
        Paper a = paper("", "资本结构与公司治理", "《金融研究》", "2020-03");
        Paper b = paper("", "资本结构与公司治理", "《金融研究》", "2020-03");
        Paper c = paper("", "资本结构与公司治理", "《金融研究》", "2021-03");
        assertEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
        assertNotEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(c));
    }

    @Test
    @DisplayName("null 与空串等价，不因缺字段而漏判")
    void nullEqualsEmpty() {
        Paper a = paper(null, "标题", "《期刊》", "2020-01");
        Paper b = paper("", "标题", "《期刊》", "2020-01");
        assertEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("大小写与首尾空格归一化")
    void normalizesCaseAndWhitespace() {
        Paper a = paper("10.1/X", "Audit Quality", "The Accounting Review", "2019-05");
        Paper b = paper(" 10.1/x ", "  audit quality", "THE ACCOUNTING REVIEW ", "2019-05 ");
        assertEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("相邻字段拼接不串味：ab|c 不等于 a|bc")
    void noKeyCollisionAcrossFields() {
        Paper a = paper("x", "ab", "c", "2020");
        Paper b = paper("x", "a", "bc", "2020");
        assertNotEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("同名同刊不同期 → 不同篇")
    void differentIssueIsDifferentPaper() {
        Paper a = paper("", "年度回顾", "《会计研究》", "2019-12");
        Paper b = paper("", "年度回顾", "《会计研究》", "2020-12");
        assertNotEquals(PaperDedupe.keyOf(a), PaperDedupe.keyOf(b));
    }

    @Test
    @DisplayName("Document 建键与 Paper 建键结果一致（投影查询走这条路）")
    void documentKeyMatchesPaperKey() {
        Paper p = paper("10.1/y", "Tax Research", "Journal of Finance", "2021-07");
        org.bson.Document doc = new org.bson.Document()
                .append("paperId", "10.1/y").append("title", "Tax Research")
                .append("journalName", "Journal of Finance").append("publishDate", "2021-07");
        assertEquals(PaperDedupe.keyOf(p), PaperDedupe.keyOf(doc));
    }

    @Test
    @DisplayName("Document 缺字段按空串处理，不抛异常")
    void documentWithMissingFields() {
        org.bson.Document doc = new org.bson.Document().append("title", "只有标题");
        assertEquals(PaperDedupe.keyOf(null, "只有标题", null, null), PaperDedupe.keyOf(doc));
    }
}
