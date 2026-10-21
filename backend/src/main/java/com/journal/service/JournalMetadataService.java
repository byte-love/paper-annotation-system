package com.journal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 期刊元数据服务
 * 根据需求文档规则：
 * 1. 根据期刊名判断 Type（Accounting / Finance）
 * 2. 给定期刊缩写（JournalAbbName）
 * 3. 根据标题/摘要含 China/Chinese 判断是否为"国际中文期刊"
 */
@Slf4j
@Service
public class JournalMetadataService {

    // ===== 正式期刊列表（来自需求文档） =====

    private static final Set<String> ACCOUNTING_JOURNALS = new LinkedHashSet<>(Arrays.asList(
            "The Accounting Review",
            "Journal of Accounting and Economics",
            "Journal of Accounting Research",
            "Contemporary Accounting Research",
            "Review of Accounting Studies",
            "Accounting, Organizations and Society",
            "Auditing: A Journal of Practice & Theory",
            "Journal of Accounting, Auditing and Finance",
            "Journal of Accounting and Public Policy",
            "Journal of Business Finance & Accounting",
            "Journal of Management Accounting Research"
    ));

    private static final Set<String> FINANCE_JOURNALS = new LinkedHashSet<>(Arrays.asList(
            "The Journal of Finance",
            "Journal of Financial and Quantitative Analysis",
            "Journal of Financial Economics",
            "The Review of Financial Studies",
            "Financial Management",
            "Journal of Banking & Finance",
            "Journal of Corporate Finance",
            "Journal of Empirical Finance",
            "Journal of Financial Intermediation"
    ));

    /** 期刊名 → 缩写映射 */
    private static final Map<String, String> ABB_MAP = new LinkedHashMap<>();
    static {
        ABB_MAP.put("The Accounting Review",                  "TAR");
        ABB_MAP.put("Journal of Accounting and Economics",    "JAE");
        ABB_MAP.put("Journal of Accounting Research",         "JAR");
        ABB_MAP.put("Contemporary Accounting Research",       "CAR");
        ABB_MAP.put("Review of Accounting Studies",           "RAST");
        ABB_MAP.put("Accounting, Organizations and Society",  "AOS");
        ABB_MAP.put("Auditing: A Journal of Practice & Theory", "AJPT");
        ABB_MAP.put("Journal of Accounting, Auditing and Finance", "JAAF");
        ABB_MAP.put("Journal of Accounting and Public Policy", "JAPP");
        ABB_MAP.put("Journal of Business Finance & Accounting", "JBFA");
        ABB_MAP.put("Journal of Management Accounting Research", "JMAR");
        ABB_MAP.put("The Journal of Finance",                 "JF");
        ABB_MAP.put("Journal of Financial and Quantitative Analysis", "JFQA");
        ABB_MAP.put("Journal of Financial Economics",         "JFE");
        ABB_MAP.put("The Review of Financial Studies",        "RFS");
        ABB_MAP.put("Financial Management",                   "FM");
        ABB_MAP.put("Journal of Banking & Finance",           "JBF");
        ABB_MAP.put("Journal of Corporate Finance",           "JCF");
        ABB_MAP.put("Journal of Empirical Finance",           "JEF");
        ABB_MAP.put("Journal of Financial Intermediation",    "JFI");
    }

    /** 获取 Type：Accounting / Finance / null(国内) */
    public String getType(String journalName) {
        if (journalName == null) return null;
        String cleaned = journalName.replace("《", "").replace("》", "").trim();
        if (ACCOUNTING_JOURNALS.contains(cleaned)) return "Accounting";
        if (FINANCE_JOURNALS.contains(cleaned)) return "Finance";

        // 模糊匹配：去除 The 前缀后比较
        for (String aj : ACCOUNTING_JOURNALS) {
            if (cleaned.equalsIgnoreCase(aj) || cleaned.equalsIgnoreCase(aj.replace("The ", ""))) {
                return "Accounting";
            }
        }
        for (String fj : FINANCE_JOURNALS) {
            if (cleaned.equalsIgnoreCase(fj) || cleaned.equalsIgnoreCase(fj.replace("The ", ""))) {
                return "Finance";
            }
        }
        return null;
    }

    /** 获取期刊缩写 */
    public String getAbbName(String journalName) {
        if (journalName == null) return null;
        String cleaned = journalName.replace("《", "").replace("》", "").trim();
        String abb = ABB_MAP.get(cleaned);
        if (abb != null) return abb;

        // 尝试去掉 The 前缀再查
        for (Map.Entry<String, String> e : ABB_MAP.entrySet()) {
            if (cleaned.equalsIgnoreCase(e.getKey())
                    || cleaned.equalsIgnoreCase(e.getKey().replace("The ", ""))) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 判断是否为国际中文期刊（标题或摘要含 China / Chinese） */
    public boolean isInternationalChinese(String title, String abstractText) {
        String combined = (title != null ? title : "") + " " + (abstractText != null ? abstractText : "");
        String lower = combined.toLowerCase();
        return lower.contains("china") || lower.contains("chinese");
    }
}
