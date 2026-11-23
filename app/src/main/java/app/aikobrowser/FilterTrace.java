package app.aikobrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Volatile diagnostics for a single top-level document. Only blocked requests are recorded. */
final class FilterTrace {
    static final int LIMIT = 12;
    private static final int MAX_REASON = 260;
    private long count;
    private ArrayList<Hit> hits;

    static final class Hit {
        final String host, source, reason;
        final Object key;

        Hit(String host, String source, String reason, Object key) {
            this.host = host;
            this.source = source;
            this.reason = reason;
            this.key = key;
        }
    }

    static final class Snapshot {
        final long count;
        final List<Hit> recent;

        Snapshot(long count, List<Hit> recent) {
            this.count = count;
            this.recent = recent;
        }
    }

    synchronized void add(String host, FilterRules rules, FilterRules.Rule rule) {
        if (record(host, rule)) append(host, rule.source, rules.description(rule), rule);
    }

    synchronized void addBootstrap(String host, String domain) {
        if (record(host, domain)) append(host, "启动基础过滤", domain, domain);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(count, hits == null ? Collections.emptyList() : new ArrayList<>(hits));
    }

    private boolean record(String host, Object key) {
        count++;
        if (hits == null) hits = new ArrayList<>(LIMIT);
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            if (hit.key == key && hit.host.equals(host)) {
                if (i != 0) hits.add(0, hits.remove(i));
                return false;
            }
        }
        return true;
    }

    private void append(String host, String source, String reason, Object key) {
        if (hits.size() == LIMIT) hits.remove(LIMIT - 1);
        hits.add(0, new Hit(host, source, reason.length() > MAX_REASON
                ? reason.substring(0, MAX_REASON) + "…" : reason, key));
    }
}
