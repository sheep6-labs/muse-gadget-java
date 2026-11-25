/*
 * Derived from the Muse Gadgets SDK (https://github.com/facebookincubator/muse-gadget-sdk),
 * Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0.
 * This file re-implements the SDK's server_hosts.py in Java (not a verbatim copy); see NOTICE.
 */
package dev.musegadget.core.net;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The servers the device may send its account and VM credentials to. Pairing names them
 * (provision_v2's api_url, api_url_v2 and noise_host); apply these checks before connecting.
 * Values match exactly, as the Muse app sends them. Null or empty means the default and always passes.
 * Mirrors SDK linux server_hosts.py.
 */
public final class ServerHosts {
    /** API base URLs (origin only; API paths are appended): api_url_v2 and the default, then api_url (older firmware). */
    public static final List<String> API_URLS =
            Collections.unmodifiableList(Arrays.asList("https://api.muse.ai", "https://hatch-api.meta.ai"));
    /** The Noise host: a bare hostname, dialled on port 443. Also the default. */
    public static final String NOISE_HOST = "hatch.metaaivm.com";
    public static final List<String> NOISE_HOSTS = Collections.singletonList(NOISE_HOST);

    /** System property that lifts the check for development ({@code 1} or {@code true}). */
    public static final String CUSTOM_PROPERTY = "musegadget.customServerHosts";
    /** Environment variable that lifts the check for development ({@code 1}), as in the SDK. */
    public static final String CUSTOM_ENV = "MUSEGADGET_CUSTOM_SERVER_HOSTS";

    private ServerHosts() {}

    /** True when the development escape hatch is on: any host is then accepted. */
    public static boolean customServerHostsEnabled() {
        String p = System.getProperty(CUSTOM_PROPERTY);
        if (p != null && (p.equals("1") || p.equals("true"))) return true;
        return "1".equals(System.getenv(CUSTOM_ENV));
    }

    public static boolean apiAllowed(String url) { return listed(url, API_URLS); }

    public static boolean noiseAllowed(String host) { return listed(host, NOISE_HOSTS); }

    /** The Noise host to dial: {@code saved}, or the default when it is empty or not allowed. */
    public static String noiseHost(String saved) {
        if (!noiseAllowed(saved)) return NOISE_HOST;
        return saved != null && !saved.isEmpty() ? saved : NOISE_HOST;
    }

    private static boolean listed(String value, List<String> allowed) {
        if (value == null || value.isEmpty()) return true;
        return customServerHostsEnabled() || allowed.contains(value);
    }
}
