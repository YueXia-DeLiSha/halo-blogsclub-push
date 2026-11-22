package run.halo.blogsclub.push;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * BlogsClub 推送插件入口。
 *
 * <p>插件启动后延迟 30 秒执行首次同步，之后每 5 分钟扫描一次已发布文章，
 * 通过 Halo 内置的 ConfigMap 记录已推送文章用于去重。控制台也提供手动推送按钮。</p>
 */
@Component
public class BlogsClubPushPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(BlogsClubPushPlugin.class);

    private static final Duration FIRST_DELAY = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofMinutes(5);

    private final BlogsClubPushService pushService;

    private Disposable subscription;

    public BlogsClubPushPlugin(PluginContext pluginContext, BlogsClubPushService pushService) {
        super(pluginContext);
        this.pushService = pushService;
    }

    @Override
    public void start() {
        subscription = Flux.concat(
                Mono.delay(FIRST_DELAY),
                Flux.interval(INTERVAL, INTERVAL)
            )
            .onBackpressureDrop()
            .concatMap(tick -> pushService.syncOnce()
                .doOnNext(result -> {
                    if (result.disabled()) {
                        log.info("[blogsclub-push] 跳过本轮：{}", result.message());
                    } else {
                        log.info("[blogsclub-push] 同步完成：{}", result.message());
                    }
                })
                .onErrorResume(e -> {
                    log.error("[blogsclub-push] 同步失败：{}", e.toString());
                    return Mono.empty();
                })
                .then())
            .subscribe();

        log.info("[blogsclub-push] 插件已启动，每 {} 分钟扫描一次", INTERVAL.toMinutes());
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
            subscription = null;
        }
        log.info("[blogsclub-push] 插件已停止");
    }
}