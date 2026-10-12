package tw.com.fmt.mollm;

import java.util.ArrayList;
import java.util.List;

/** UI submissions are consumed at complete assistant/tool boundaries. */
final class AgentInbox {
    private final List<Gateway.Message> pending = new ArrayList<>();
    private boolean open = true;
    synchronized boolean submit(Gateway.Message message) {
        if (!open) return false;
        if (pending.size() >= 16) throw new IllegalStateException("待處理引導訊息最多 16 則，請等 Agent 讀取後再送。");
        long bytes = pending.stream().flatMap(value -> value.images().stream()).mapToLong(ImageAttachment::bytes).sum();
        if (bytes + message.images().stream().mapToLong(ImageAttachment::bytes).sum() > ImageAttachment.MAX_TOTAL_BYTES)
            throw new IllegalStateException("待處理引導訊息的圖片合計不可超過 20 MiB。");
        pending.add(message); return true;
    }
    synchronized List<Gateway.Message> drain() {
        var messages = List.copyOf(pending); pending.clear(); return messages;
    }
    synchronized int pendingCount() { return pending.size(); }
    synchronized boolean finishIfEmpty() {
        if (!pending.isEmpty()) return false;
        open = false; return true;
    }
    synchronized List<Gateway.Message> close() { open = false; return drain(); }
}
