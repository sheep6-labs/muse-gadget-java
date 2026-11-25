import dev.musegadget.core.net.ServerHosts;

/** Server-host allowlist: the SDK's cases (exact match only) plus the custom-hosts escape hatch. */
public final class ServerHostsTest {
    static final String[] API_ALLOWED = {"https://api.muse.ai", "https://hatch-api.meta.ai"};
    static final String[] API_REFUSED = {
        "http://api.muse.ai", "HTTPS://api.muse.ai", "https://API.MUSE.AI", "api.muse.ai", "//api.muse.ai",
        "https://api.muse.ai/", "https://api.muse.ai/hatch", "https://api.muse.ai?x=1", "https://api.muse.ai#x",
        "https://api.muse.ai.", "https://api.muse.ai:443", "https://api.muse.ai:8443", "https://user@api.muse.ai",
        "https://api.muse.ai@evil.example", "https://api.muse.ai\\@evil.example", "https://evil.example/https://api.muse.ai",
        "https://api.muse.ai.evil.example", "https://evilapi.muse.ai", "https://evil.api.muse.ai", "https://muse.ai",
        "https://evilmuse.ai", "https://meta.ai", "https://evil.meta.ai", "https://www.meta.ai",
        "https://hatch-api.meta.ai.evil.example", "https://hatch.metaaivm.com", "https://vm-1.metaaivm.com",
        " https://api.muse.ai", "https://api.muse.ai ", "https://api.muse.ai\r\nX-Injected: 1", "wss://api.muse.ai"};
    static final String[] NOISE_ALLOWED = {"hatch.metaaivm.com"};
    static final String[] NOISE_REFUSED = {
        "evilmetaaivm.com", "metaaivm.com", "metaaivm.com.evil.com", "hatch.metaaivm.com.evil.com", "evil.hatch.metaaivm.com",
        "hatchmetaaivm.com", "vm-1.metaaivm.com", "0123abcd-0000-4000-8000-000000000000.metaaivm.com", "HATCH.METAAIVM.COM",
        "hatch.metaaivm.com.", "hatch.metaaivm.com:443", "hatch.metaaivm.com:8443", "user@hatch.metaaivm.com",
        "hatch.metaaivm.com@evil.com", "https://hatch.metaaivm.com", "wss://hatch.metaaivm.com", "hatch.metaaivm.com/",
        "hatch.metaaivm.com/v1/noise", "hatch.metaaivm.com?vm_id=x", "hatch.metaaivm.com\r\nX-Injected: 1",
        " hatch.metaaivm.com", "hatch.metaaivm.com ", "api.muse.ai", "evil.example"};

    static boolean contains(String[] a, String v) {
        for (String s : a) if (s.equals(v)) return true;
        return false;
    }

    static void allowlist(String n, boolean custom) {
        boolean apiOk = true, noiseOk = true;
        String apiBad = "", noiseBad = "";
        for (String v : API_ALLOWED) if (ServerHosts.apiAllowed(v) != true) { apiOk = false; apiBad = v; }
        for (String v : API_REFUSED) if (ServerHosts.apiAllowed(v) != custom) { apiOk = false; apiBad = v; }
        for (String v : NOISE_ALLOWED) if (ServerHosts.noiseAllowed(v) != true) { noiseOk = false; noiseBad = v; }
        for (String v : NOISE_REFUSED) if (ServerHosts.noiseAllowed(v) != custom) { noiseOk = false; noiseBad = v; }
        T.check(n + "API hosts", apiOk, apiBad);
        T.check(n + "Noise hosts", noiseOk, noiseBad);
        T.check(n + "null/empty is the default, always allowed",
                ServerHosts.apiAllowed(null) && ServerHosts.apiAllowed("") && ServerHosts.noiseAllowed(null) && ServerHosts.noiseAllowed(""));
    }

    static void noiseHost(String n, boolean custom) {
        T.eq(n + "allowed saved host kept", "hatch.metaaivm.com", ServerHosts.noiseHost("hatch.metaaivm.com"));
        T.eq(n + "null -> default", "hatch.metaaivm.com", ServerHosts.noiseHost(null));
        T.eq(n + "empty -> default", "hatch.metaaivm.com", ServerHosts.noiseHost(""));
        boolean ok = true;
        String bad = "";
        for (String v : NOISE_REFUSED) {
            String want = custom ? v : "hatch.metaaivm.com";
            if (!want.equals(ServerHosts.noiseHost(v))) { ok = false; bad = v; }
        }
        T.check(n + (custom ? "any saved host kept" : "other saved hosts -> default"), ok, bad);
    }

    public static void main(String[] args) {
        boolean envOn = "1".equals(System.getenv(ServerHosts.CUSTOM_ENV));
        System.clearProperty(ServerHosts.CUSTOM_PROPERTY);
        if (envOn) {
            T.check("environment opt-in enables custom hosts", ServerHosts.customServerHostsEnabled());
        } else {
            T.check("custom hosts off by default", !ServerHosts.customServerHostsEnabled());
            allowlist("default: ", false);
            noiseHost("default: ", false);
            T.check("constants: default Noise host is the only Noise host",
                    ServerHosts.NOISE_HOSTS.size() == 1 && ServerHosts.NOISE_HOSTS.contains(ServerHosts.NOISE_HOST));
            T.check("constants: API list", ServerHosts.API_URLS.size() == 2 && contains(API_ALLOWED, ServerHosts.API_URLS.get(0))
                    && contains(API_ALLOWED, ServerHosts.API_URLS.get(1)));

            // Only 1/true opt in through the property.
            for (String v : new String[] {"0", "yes", "", "TRUE2"}) {
                System.setProperty(ServerHosts.CUSTOM_PROPERTY, v);
                T.check("property \"" + v + "\" does not opt in",
                        !ServerHosts.customServerHostsEnabled() && !ServerHosts.apiAllowed("https://api.example"));
            }
            for (String v : new String[] {"1", "true"}) {
                System.setProperty(ServerHosts.CUSTOM_PROPERTY, v);
                T.check("property \"" + v + "\" opts in", ServerHosts.customServerHostsEnabled());
                allowlist("custom(" + v + "): ", true);
                noiseHost("custom(" + v + "): ", true);
            }
            System.clearProperty(ServerHosts.CUSTOM_PROPERTY);
        }
        T.done();
    }
}
