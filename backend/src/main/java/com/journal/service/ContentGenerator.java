package com.journal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Content HTML 生成器
 * 根据论文类型，用对应模板把字段拼成 Content HTML
 */
@Slf4j
@Service
public class ContentGenerator {

    /**
     * 生成国内论文 Content HTML
     */
    public String generateDomestic(String title, String journalName, String publishDate,
                                   String authors, String abstractText, String keywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"MsoNormal\" align=\"center\" style=\"margin-top:15.0pt;margin-right:0cm;\n");
        sb.append("margin-bottom:7.5pt;margin-left:0cm;text-align:center;mso-pagination:widow-orphan;\n");
        sb.append("mso-outline-level:3\"> <b><span class=\"papertitle\" style=\"font-size: 12pt; font-family: 宋体;\">");
        sb.append(escape(title));
        sb.append("</span></b> <b><span lang=\"EN-US\" style=\"font-size: 12pt; font-family: Helvetica, sans-serif;\">\n");
        sb.append("   <o:p></o:p></span></b> </p> \n");

        sb.append("<p class=\"MsoNormal\" align=\"center\" style=\"margin-top:7.5pt;margin-right:0cm;\n");
        sb.append("margin-bottom:7.5pt;margin-left:0cm;text-align:center;mso-pagination:widow-orphan;\n");
        sb.append("mso-outline-level:5\"> <span class=\"papertype\" lang=\"EN-US\" style=\"font-size: 10pt; font-family: 宋体;\">");
        sb.append(escape(journalName));
        sb.append("</span> <span class=\"paperjournal\" style=\"font-size: 10pt; font-family: 宋体;\">");
        sb.append(escape(publishDate));
        sb.append("</span><br><span style=\"font-size: 10pt; font-family: 宋体;\">");
        sb.append(escape(authors));
        sb.append("</span>  <b><span lang=\"EN-US\" style=\"font-size: 10pt; font-family: Helvetica, sans-serif;\">\n");
        sb.append("   <o:p></o:p></span></b> </p> \n");

        sb.append("<p class=\"MsoNormal\" align=\"center\" style=\"margin-bottom:7.5pt;text-align:center;\n");
        sb.append("mso-pagination:widow-orphan\"><b><span style=\"font-size: 10pt; font-family: 宋体;\">摘要</span></b>");
        sb.append("<span lang=\"EN-US\" style=\"font-size: 10pt; font-family: Helvetica, sans-serif;\">\n");
        sb.append("  <o:p></o:p></span></p> \n");

        sb.append("<p class=\"MsoNormal\" align=\"left\" style=\"margin-bottom: 7.5pt;\"> ");
        sb.append("<span class=\"paperabstract\" style=\"font-size: 11pt; font-family: 宋体;\">");
        sb.append(escape(abstractText));
        sb.append("</span> <span lang=\"EN-US\" style=\"font-size: 10pt; font-family: Helvetica, sans-serif;\">\n");
        sb.append("  <o:p></o:p></span> </p> \n");

        sb.append("<p style=\"margin-bottom:15px;\"><b><span style=\"font-size:10.0pt;\n");
        sb.append("font-family:宋体;mso-ascii-font-family:Calibri;mso-ascii-theme-font:minor-latin;\n");
        sb.append("mso-fareast-theme-font:minor-fareast;mso-hansi-font-family:Calibri;mso-hansi-theme-font:\n");
        sb.append("minor-latin;mso-bidi-font-family:&quot;Times New Roman&quot;;mso-bidi-theme-font:minor-bidi;\n");
        sb.append("mso-ansi-language:EN-US;mso-fareast-language:ZH-CN;mso-bidi-language:AR-SA\">关键字：</span></b> ");
        sb.append("<span class=\"paperkeywords\" style=\"font-size:10.0pt;font-family:宋体;\">");
        sb.append(escape(keywords));
        sb.append("</span></p>");

        return sb.toString();
    }

    /**
     * 生成国际论文 Content HTML（中英文同模板）
     */
    public String generateInternational(String title, String journalName, String publishDate,
                                        String authors, String abstractText, String keywords,
                                        String sourceUrl) {
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append("<h3 style=\"text-align:center\"> <span lang=\"EN-US\"> ");
        sb.append("<font class=\"papertitle\" face=\"Times New Roman\">");
        sb.append(escape(title));
        sb.append("</font> </span> </h3> \n");

        // 期刊信息 + 作者（每个作者一个 span）
        sb.append("<h5 style=\"text-align:center\"> <span style=\"font-weight: normal;\"> ");
        sb.append("<font face=\"Times New Roman\"> ");
        sb.append("<span class=\"papertype\" lang=\"EN-US\">").append(escape(journalName)).append("<br></span> ");
        sb.append("<span class=\"paperjournal\" lang=\"EN-US\">").append(escape(publishDate)).append("<br></span>");
        if (authors != null && !authors.isEmpty()) {
            String[] authorArr = authors.split("[;；]");
            for (String author : authorArr) {
                String a = author.trim();
                if (!a.isEmpty()) {
                    sb.append("<span class=\"paperauthor\" lang=\"EN-US\">").append(escape(a)).append("<br></span> ");
                }
            }
        }
        sb.append(" </font> </span> </h5> \n");

        // 摘要标题
        sb.append("<p style=\"text-align:center\"> <b><span lang=\"EN-US\"> ");
        sb.append("<font face=\"Times New Roman\">Abstract</font> </span></b> </p> \n");

        // 摘要内容
        sb.append("<p class=\"MsoNormal paperabstractp\"> <span lang=\"EN-US\"> ");
        sb.append("<font class=\"paperabstract\" face=\"Times New Roman\">");
        sb.append(escape(abstractText));
        sb.append("</font> </span> </p>\n");

        // 关键词
        sb.append("<p class=\"MsoNormal\"> <span lang=\"EN-US\"> ");
        sb.append("<font class=\"paperkeywords\" face=\"Times New Roman\"> <b>Keywords: </b> ");
        sb.append(escape(keywords));
        sb.append(" \n   <o:p></o:p> </font> </span> </p>  \n");

        // 链接
        sb.append("<p class=\"MsoNormal\"><span lang=\"EN-US\"><font face=\"Times New Roman\">&nbsp;</font></span></p> \n");
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            sb.append("<p class=\"MsoNormal\"> <font face=\"Times New Roman\"><b>链接：</b>");
            sb.append("<a class=\"paperlink\" href=\"").append(escapeAttr(sourceUrl)).append("\"");
            sb.append(" style=\"font-size: 10.5pt; color: rgb(0, 0, 0);\">");
            sb.append(escape(sourceUrl)).append("</a></font> </p>");
        }

        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttr(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("\"", "&quot;");
    }
}
