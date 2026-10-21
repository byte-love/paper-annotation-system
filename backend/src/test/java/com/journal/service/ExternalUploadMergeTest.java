package com.journal.service;

import com.journal.model.Paper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传外网的合并逻辑测试 — 重点是软删除的墓碑能不能拦住归档副本
 *
 * 背景：推送到 150 是全量覆盖，范围 = 工作集合 + 归档集合里工作集合缺失的部分。
 * 用户在论文列表删掉一篇回补来的历史论文时，归档集合（只读兜底）里还留着同一篇，
 * 如果不按墓碑的去重键拦掉，那篇论文会被当成"工作集合缺失的历史数据"重新推上 150，
 * 删除就白删了。
 */
class ExternalUploadMergeTest {

    private Paper paper(String title, String journal, String publishDate, String relateDBs) {
        return Paper.builder()
                .paperId("").title(title).journalName(journal)
                .publishDate(publishDate).relateDBs(relateDBs)
                .build();
    }

    private Set<String> titlesOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("PaperTitle")).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("没有墓碑时，归档里工作集合缺失的论文照常补上（原有兜底行为不变）")
    void archiveFillsGapsWhenNothingDeleted() {
        Paper work = paper("工作集合里的论文", "《会计研究》", "2026年03期", "CPTD");
        Paper onlyInArchive = paper("只在归档里的老论文", "《金融研究》", "2019年06期", "DARD");

        List<Map<String, Object>> merged =
                ExternalUploadService.mergeAndSort(List.of(onlyInArchive), List.of(work), Set.of());

        assertEquals(2, merged.size());
        assertTrue(titlesOf(merged).contains("只在归档里的老论文"));
    }

    @Test
    @DisplayName("墓碑拦住归档副本：删掉的历史论文不会被归档数据重新推上 150")
    void tombstoneBlocksArchiveCopy() {
        Paper work = paper("留下的论文", "《会计研究》", "2026年03期", "CPTD");
        Paper deletedPaper = paper("被删掉的老论文", "《金融研究》", "2019年06期", "DARD");
        // 归档里同时有"留下的"和"被删掉的"两篇的副本
        List<Paper> archive = List.of(
                paper("留下的论文", "《会计研究》", "2026年03期", "CPTD"),
                deletedPaper);

        Set<String> deletedKeys = Set.of(PaperDedupe.keyOf(deletedPaper));

        List<Map<String, Object>> merged =
                ExternalUploadService.mergeAndSort(archive, List.of(work), deletedKeys);

        assertEquals(1, merged.size(), "只剩没被删的那一篇");
        assertEquals("留下的论文", merged.get(0).get("PaperTitle"));
        assertFalse(titlesOf(merged).contains("被删掉的老论文"));
    }

    @Test
    @DisplayName("墓碑按去重键匹配，归档副本的大小写/空格差异也拦得住")
    void tombstoneMatchesNormalizedKey() {
        Paper deletedPaper = paper("Audit Quality", "The Accounting Review", "2019-05", "");
        // 归档里的同一篇，大小写和空格不一致
        Paper archiveCopy = paper("  audit quality", "THE ACCOUNTING REVIEW ", "2019-05 ", "");

        List<Map<String, Object>> merged = ExternalUploadService.mergeAndSort(
                List.of(archiveCopy), List.of(), Set.of(PaperDedupe.keyOf(deletedPaper)));

        assertTrue(merged.isEmpty(), "归一化后是同一篇，应被墓碑拦掉");
    }

    @Test
    @DisplayName("工作集合优先于归档：同一篇不会重复推送，且用工作集合的最新关联")
    void workCollectionWinsOverArchive() {
        Paper work = paper("同一篇论文", "《会计研究》", "2026年03期", "CPTD,DARD");
        Paper stale = paper("同一篇论文", "《会计研究》", "2026年03期", "旧的关联");

        List<Map<String, Object>> merged =
                ExternalUploadService.mergeAndSort(List.of(stale), List.of(work), Set.of());

        assertEquals(1, merged.size());
        assertEquals("CPTD,DARD", merged.get(0).get("RelateDBs"));
    }

    @Test
    @DisplayName("墓碑不影响工作集合：只拦归档副本，没删的论文照常推送")
    void tombstoneDoesNotTouchWorkPapers() {
        Paper work = paper("在推送范围里的论文", "《会计研究》", "2026年03期", "CPTD");
        // 一个和任何论文都不匹配的墓碑键
        Set<String> unrelatedTombstone = Set.of(PaperDedupe.keyOf(
                paper("别的论文", "《别的期刊》", "2020年01期", "")));

        List<Map<String, Object>> merged =
                ExternalUploadService.mergeAndSort(List.of(), List.of(work), unrelatedTombstone);

        assertEquals(1, merged.size());
        assertEquals("在推送范围里的论文", merged.get(0).get("PaperTitle"));
    }

    @Test
    @DisplayName("分配连续自增 ID（150 表 ID 是主键，不能有空洞或重复）")
    void assignsSequentialIds() {
        List<Paper> work = List.of(
                paper("论文A", "《会计研究》", "2026年03期", "CPTD"),
                paper("论文B", "《会计研究》", "2025年03期", "DARD"),
                paper("论文C", "《会计研究》", "2024年03期", ""));

        List<Map<String, Object>> merged = ExternalUploadService.mergeAndSort(List.of(), work, Set.of());

        assertEquals(List.of(1, 2, 3), merged.stream().map(r -> r.get("ID")).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("墓碑拦掉归档后 ID 依然连续，不留空洞")
    void idsStaySequentialAfterTombstoneFilter() {
        Paper deletedPaper = paper("被删的", "《金融研究》", "2019年06期", "");
        List<Paper> archive = List.of(
                paper("归档保留的", "《会计研究》", "2020年01期", ""),
                deletedPaper);
        List<Paper> work = List.of(paper("工作集合的", "《会计研究》", "2026年03期", "CPTD"));

        List<Map<String, Object>> merged = ExternalUploadService.mergeAndSort(
                archive, work, Set.of(PaperDedupe.keyOf(deletedPaper)));

        assertEquals(2, merged.size());
        assertEquals(List.of(1, 2), merged.stream().map(r -> r.get("ID")).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("无关联时 RelateDBs 推空字符串，不推 null")
    void relateDBsNeverNull() {
        List<Paper> work = List.of(
                paper("relateDBs 是 null 的", "《会计研究》", "2026年03期", null),
                paper("relateDBs 是空串的", "《会计研究》", "2025年03期", ""),
                paper("有关联的", "《会计研究》", "2024年03期", "CPTD"));

        List<Map<String, Object>> merged = ExternalUploadService.mergeAndSort(List.of(), work, Set.of());

        assertEquals(3, merged.size());
        for (Map<String, Object> row : merged) {
            assertNotNull(row.get("RelateDBs"), "RelateDBs 不能为 null: " + row.get("PaperTitle"));
        }
        assertEquals("", merged.get(0).get("RelateDBs"), "null 应归成空字符串");
        assertEquals("", merged.get(1).get("RelateDBs"), "空串保持空串");
        assertEquals("CPTD", merged.get(2).get("RelateDBs"), "有关联的照原样");
    }

    @Test
    @DisplayName("归档论文的 null relateDBs 也归成空字符串")
    void archiveNullRelateDBsBecomesEmpty() {
        List<Map<String, Object>> merged = ExternalUploadService.mergeAndSort(
                List.of(paper("只在归档里的", "《金融研究》", "2019年06期", null)), List.of(), Set.of());

        assertEquals(1, merged.size());
        assertEquals("", merged.get(0).get("RelateDBs"));
    }
}
