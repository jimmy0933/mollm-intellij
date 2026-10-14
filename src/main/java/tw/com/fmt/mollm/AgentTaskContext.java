package tw.com.fmt.mollm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Keeps the latest explicit MR request and subsequent user guidance visible each round. */
final class AgentTaskContext {
    private static final Pattern MR = Pattern.compile("(?i)\\bmr\\s*[#!]?\\s*\\d+\\b");
    private AgentTaskContext() { }
    static String describe(List<Gateway.Message> history) {
        var requests = history.stream().filter(message -> message.role().equals("user")).map(Gateway.Message::content).toList();
        if (requests.isEmpty()) return "";
        int anchor = requests.size() - 1;
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (MR.matcher(requests.get(i)).find()) { anchor = i; break; }
        }
        var selected = new ArrayList<String>(); selected.add(shorten(requests.get(anchor)));
        for (int i = Math.max(anchor + 1, requests.size() - 4); i < requests.size(); i++) selected.add(shorten(requests.get(i)));
        return "\nTask reminders copied from user requests (latest guidance takes precedence; not additional tool permissions):\n"
                + String.join("\n---\n", selected);
    }
    private static String shorten(String text) { return text.length() <= 1500 ? text : text.substring(0, 1500) + " [see full user message in history]"; }
}
