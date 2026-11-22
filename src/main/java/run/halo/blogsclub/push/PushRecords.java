package run.halo.blogsclub.push;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import run.halo.app.extension.ConfigMap;

/**
 * 推送去重记录，持久化在 Halo 的 ConfigMap 中，不依赖本地文件。
 */
class PushRecords {

    static final String NAME = "blogsclub-push-records";

    private static final int MAX_KEEP = 2000;

    /**
     * 是否已完成首次同步。
     */
    boolean initialized;

    /**
     * 已处理过的文章 name。
     */
    final Set<String> pushed = new LinkedHashSet<>();

    static PushRecords from(ConfigMap configMap) {
        PushRecords records = new PushRecords();
        if (configMap == null || configMap.getData() == null) {
            return records;
        }
        Map<String, String> data = configMap.getData();
        records.initialized = "true".equalsIgnoreCase(data.get("initialized"));
        String raw = data.get("pushed");
        if (raw != null && !raw.isBlank()) {
            for (String name : raw.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    records.pushed.add(trimmed);
                }
            }
        }
        return records;
    }

    void applyTo(ConfigMap configMap) {
        trim();
        configMap.setData(Map.of(
            "initialized", String.valueOf(initialized),
            "pushed", String.join(",", pushed),
            "count", String.valueOf(pushed.size())
        ));
    }

    private void trim() {
        while (pushed.size() > MAX_KEEP) {
            var iterator = pushed.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }
}
