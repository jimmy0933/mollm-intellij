package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.Strictness;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;

/** Reject incomplete or trailing JSON before any tool, approval or side effect. */
final class ToolArguments {
    private ToolArguments() { }
    static JsonObject parse(String input) throws IOException {
        try (var reader = new JsonReader(new StringReader(input))) {
            reader.setStrictness(Strictness.STRICT);
            var value = JsonParser.parseReader(reader);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (RuntimeException | IOException e) {
            // Do not echo partial paths/content/credentials in diagnostic messages.
            throw new IOException("INVALID_TOOL_ARGUMENTS: 工具參數不是完整 JSON 物件（" + input.length()
                    + " 字元）。未執行工具；請重新產生完整參數，長檔案請改用短內容建立後 append_file 分段寫入。", e);
        }
    }
}
