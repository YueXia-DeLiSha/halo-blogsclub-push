package run.halo.blogsclub.push;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;

/**
 * 插件自定义 API，供控制台「手动推送」按钮调用。
 */
@Component
public class BlogsClubPushEndpoint implements CustomEndpoint {

    private final BlogsClubPushService pushService;

    public BlogsClubPushEndpoint(BlogsClubPushService pushService) {
        this.pushService = pushService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/trigger", request -> pushService.syncOnce()
                .flatMap(result -> ServerResponse.ok().bodyValue(result)))
            .GET("/status", request -> ServerResponse.ok().bodyValue(
                java.util.Map.of("plugin", "blogsclub-push", "version", "1.0.2")))
            .build();
    }
}