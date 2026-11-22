package run.halo.blogsclub.push;

import java.util.List;

/**
 * 一次同步的结果，用于控制台按钮回显。
 */
public record SyncResult(
    boolean ok,
    boolean disabled,
    int published,
    int pending,
    int pushed,
    int failed,
    List<String> titles,
    String message
) {

    static SyncResult disabled(String message) {
        return new SyncResult(false, true, 0, 0, 0, 0, List.of(), message);
    }

    static SyncResult ok(int published, int pending, int pushed, int failed,
                         List<String> titles, String message) {
        return new SyncResult(true, false, published, pending, pushed, failed, titles, message);
    }
}