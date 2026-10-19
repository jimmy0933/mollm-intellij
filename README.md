
## MCP (Model Context Protocol) 支援

MoLLM 現在支援透過 [MCP (Model Context Protocol)](https://modelcontextprotocol.io) 載入外部工具，讓模型能夠存取更多元的本機資源與遠端服務。

### 支援的傳輸協定
1. **Stdio (命令列傳輸)**: 可執行本機的 CLI 指令並透過標準輸入/輸出與 MCP 伺服器溝通。
   - **範例**: 使用 `npx` 啟動 `@modelcontextprotocol/server-everything`：
     - **執行檔**: `npx`
     - **參數**: `-y @modelcontextprotocol/server-everything`
2. **SSE (HTTP 串流傳輸)**: 透過 HTTP 連線至遠端或本機的 MCP 伺服器。
   - **範例**: `http://localhost:3000/sse`

### 安全認證
當您使用 SSE 傳輸並需要認證時，可以在設定頁面中填寫 `Auth Token`，並勾選「安全儲存」。
這些資訊會被加密並存放於 IntelliJ 內建的 Password Safe 當中，絕不會以明碼方式寫入設定檔或日誌中，且不會傳送給語言模型。如果需要自訂標頭，也可以透過 Header 欄位（以 `Key:Value;` 格式）指定。

### 設定匯入
在 MCP 管理介面中，支援點擊「從 JSON 匯入」按鈕，您可以選擇現有的 `mcp.json` 設定檔（例如 Claude Desktop 的配置）。匯入程式將自動解析：
- Stdio 執行檔、參數與環境變數
- SSE 的伺服器網址
如果有環境變數（包含 `$VAR` 或 `%VAR%`）參考，系統會跳出警告讓您在隨後的預覽對話框中手動設定。

### 權限控制
所有 MCP 工具執行前都會彈出單次的許可對話框，要求您手動批准（您可以決定本次是否執行或拒絕）。
這確保了所有透過 MCP 發生的動作，皆在您的明確授權之下完成。
