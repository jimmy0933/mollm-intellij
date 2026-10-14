package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Checks saved structure only; cannot certify review findings or test outcomes. */
final class ReviewReportCheck {
    private ReviewReportCheck() { }
    static String inspect(Path path, String text, boolean saved) {
        var result = new JsonObject(); var warnings = new JsonArray();
        result.addProperty("path", path.toString()); result.addProperty("chars", text.length());
        result.addProperty("lines", text.split("\n", -1).length); result.addProperty("disk_matches_editor", saved);
        if (!path.getFileName().toString().matches("mr\\d+-mollm(?:-\\d+)?\\.md")) warnings.add("報告命名應為 mr<編號>-mollm.md（可有數字後綴）。");
        if (!text.lines().anyMatch(line -> line.startsWith("# "))) warnings.add("缺少報告標題。");
        if (!Pattern.compile("(?im)^.*(?:head|審查版本)\\s*[:：].*$").matcher(text).find()) warnings.add("缺少 Head／審查版本欄位。");
        if (!Pattern.compile("(?im)^.*(?:base|比較基準)\\s*[:：].*$").matcher(text).find()) warnings.add("缺少 Base／比較基準欄位。");
        if (!text.contains("測試") && !text.toLowerCase(java.util.Locale.ROOT).contains("test")) warnings.add("缺少測試／未驗證狀態說明。");
        var declared = Pattern.compile("(\\d+)\\s*個(高|中|低)風險").matcher(text);
        boolean hasRiskTable = text.lines().anyMatch(line -> line.matches("\\s*\\|\\s*\\d+\\s*\\|\\s*[高中低]\\s*\\|.*"));
        while (declared.find()) {
            String risk = declared.group(2);
            long rows = text.lines().filter(line -> line.matches("\\s*\\|\\s*\\d+\\s*\\|\\s*" + risk + "\\s*\\|.*")).count();
            if (hasRiskTable && (declared.group(1).length() > 18 || Long.parseLong(declared.group(1)) != rows)) warnings.add(risk + "風險宣告數量與表格不一致。");
        }
        if (!saved) warnings.add("編輯器內容與磁碟不同，尚未確認儲存。");
        result.add("warnings", warnings); result.addProperty("structure_ok", warnings.isEmpty());
        result.addProperty("review_verified", false);
        result.addProperty("note", "只檢查檔案儲存與基本結構；不代表問題已重現、已編譯或已跑測試。");
        return result.toString();
    }
}
