package sair.aiagent.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 网页搜索工具 —— 使用 jsoup 抓取并解析必应（Bing）搜索结果页。
 * <p>
 * 纯 Java 零部署，无需 API Key。搜索 URL 固定为必应搜索页，不存在 SSRF 风险
 * （查询词不参与主机解析）。返回标题、链接、摘要的结构化文本，供 AI 参考。
 * </p>
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li>搜索结果项选择器：{@code li.b_algo}，标题 {@code h2 a}，摘要 {@code .b_caption p}</li>
 *   <li>自动处理 gzip / charset / 重定向（由 jsoup 内置 HTTP 客户端完成）</li>
 *   <li>结果上限 {@value #MAX_RESULTS} 条，避免上下文膨胀</li>
 * </ul>
 */
public final class SearchTool {

    /** 最多返回的结果条数 */
    private static final int MAX_RESULTS = 8;

    /** 单次搜索超时（毫秒） */
    private static final int TIMEOUT_MS = 10_000;

    /** 模拟现代浏览器 UA，降低被反爬拦截的概率 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private SearchTool() {}

    /**
     * 执行一次网页搜索。
     *
     * @param query 搜索关键词
     * @return 格式化的搜索结果文本，失败返回以 {@code [search]} 开头的错误描述
     */
    public static String search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "[search] 请提供搜索关键词";
        }
        query = query.trim();
        String url;
        try {
            url = "https://www.bing.com/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                    + "&count=20&mkt=zh-CN";
        } catch (Exception e) {
            return "[search] 搜索关键词编码失败: " + e.toString();
        }

        Document doc = null;
        Exception last = null;
        for (int attempt = 0; attempt < 3 && doc == null; attempt++) {
            try {
                doc = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .get();
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    try { Thread.sleep(600L * (attempt + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        try {
            if (doc == null) {
                return "[search] 搜索失败: " + (last != null ? last.toString() : "网络异常");
            }

            Elements items = doc.select("li.b_algo");
            if (items.isEmpty()) {
                return "[search] 未获取到搜索结果（可能被反爬拦截，请稍后重试）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[搜索] \"").append(query).append("\" 的结果：\n\n");

            int n = 0;
            for (Element item : items) {
                Element a = item.selectFirst("h2 a");
                if (a == null) continue;

                String title = a.text().trim();
                String link = a.absUrl("href");
                Element cap = item.selectFirst(".b_caption p, .b_caption, p");
                String snippet = cap != null ? cap.text().trim() : "";

                sb.append(++n).append(". ").append(title).append('\n');
                if (!link.isEmpty()) sb.append("   ").append(link).append('\n');
                if (!snippet.isEmpty()) sb.append("   ").append(snippet).append('\n');
                sb.append('\n');

                if (n >= MAX_RESULTS) break;
            }

            if (n == 0) {
                return "[search] 未解析到有效结果";
            }
            return sb.toString().trim();

        } catch (Exception e) {
            return "[search] 搜索失败: " + e.toString();
        }
    }
}
