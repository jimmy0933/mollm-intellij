package tw.com.fmt.mollm;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;

final class ApiKeys {
    private ApiKeys() { }
    private static CredentialAttributes attributes(String id) {
        return new CredentialAttributes("MoLLM IntelliJ - " + id);
    }
    // PasswordSafe is blocking: callers must run these methods on a worker thread.
    static String get(String id) {
        String value = PasswordSafe.getInstance().getPassword(attributes(id));
        return value == null ? "" : value;
    }
    static void set(String id, String value) {
        PasswordSafe.getInstance().setPassword(attributes(id), value.isBlank() ? null : value);
    }
}
