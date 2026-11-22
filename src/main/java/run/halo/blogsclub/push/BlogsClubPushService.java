package run.halo.blogsclub.push;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 扫描已发布文章并推送到 BlogsClub（BC-PUSH-V2 签名）。
 */
@Component
public class BlogsClubPushService {

    private static final Logger log = LoggerFactory.getLogger(BlogsClubPushService.class);

    private static final String SETTING_GROUP = "basic";
    private static final String PUSH_URL = "https://www.blogsclub.org/openPush";
    private static final int MAX_PER_CYCLE = 10;
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final WebClient webClient;
    private final SecureRandom random = new SecureRandom();

    public BlogsClubPushService(ReactiveExtensionClient client,
                                ReactiveSettingFetcher settingFetcher,
                                ExternalUrlSupplier externalUrlSupplier) {
        this.client = client;
        this.settingFetcher = settingFetcher;
        this.externalUrlSupplier = externalUrlSupplier;
        this.webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.USER_AGENT, "Halo-BlogsClubPush/1.0.2")
            .build();
    }

    /**
     * 执行一次同步：读取配置、比对去重记录、推送新增文章。
     */
    public Mono<SyncResult> syncOnce() {
        return settingFetcher.fetch(SETTING_GROUP, PushSettings.class)
            .defaultIfEmpty(new PushSettings())
            .flatMap(settings -> {
                if (!Boolean.TRUE.equals(settings.getStatus())) {
                    return Mono.just(SyncResult.disabled("推送未开启，请先到插件设置中打开"));
                }
                if (isBlank(settings.getPushId()) || isBlank(settings.getPushKey())) {
                    return Mono.just(SyncResult.disabled("PushID 或 PushKey 未填写"));
                }
                return syncWith(settings);
            });
    }

    private Mono<SyncResult> syncWith(PushSettings settings) {
        return loadRecords()
            .flatMap(records -> listPublishedPosts()
                .collectList()
                .flatMap(posts -> {
                    List<Post> targets = pickTargets(posts, records, settings);
                    if (targets.isEmpty()) {
                        return saveRecords(records)
                            .thenReturn(SyncResult.ok(posts.size(), 0, 0, 0, List.of(),
                                "没有需要推送的新文章"));
                    }
                    log.info("[blogsclub-push] 本轮待推送 {} 篇", targets.size());
                    List<String> pushedNames = new ArrayList<>();
                    List<String> pushedTitles = new ArrayList<>();
                    List<String> failedTitles = new ArrayList<>();
                    boolean[] aborted = {false};
                    return Flux.fromIterable(targets)
                        .concatMap(post -> aborted[0] ? Mono.empty()
                            : push(post, settings)
                                .then(Mono.fromRunnable(() -> {
                                    pushedNames.add(post.getMetadata().getName());
                                    pushedTitles.add(post.getSpec().getTitle());
                                }))
                                .onErrorResume(e -> {
                                    aborted[0] = true;
                                    failedTitles.add(post.getSpec().getTitle());
                                    return Mono.empty();
                                })
                                .then(Mono.delay(Duration.ofSeconds(1)).then()))
                        .then(Mono.defer(() -> {
                            records.pushed.addAll(pushedNames);
                            log.info("[blogsclub-push] 本轮成功推送 {} 篇", pushedNames.size());
                            String message = failedTitles.isEmpty()
                                ? "推送完成，成功 " + pushedNames.size() + " 篇"
                                : "推送中断，成功 " + pushedNames.size() + " 篇，失败 "
                                    + failedTitles.size() + " 篇";
                            return saveRecords(records).thenReturn(SyncResult.ok(
                                posts.size(), targets.size(), pushedNames.size(),
                                failedTitles.size(), pushedTitles, message));
                        }));
                }));
    }

    private List<Post> pickTargets(List<Post> posts, PushRecords records, PushSettings settings) {
        if (!records.initialized) {
            int limit = settings.getPosts() == null ? 10 : Math.max(0, settings.getPosts());
            List<Post> selected = posts.stream().limit(limit).toList();
            posts.forEach(post -> records.pushed.add(post.getMetadata().getName()));
            records.initialized = true;
            log.info("[blogsclub-push] 首次同步：现有已发布文章 {} 篇，本次推送最近 {} 篇",
                posts.size(), selected.size());
            return selected;
        }
        return posts.stream()
            .filter(post -> !records.pushed.contains(post.getMetadata().getName()))
            .limit(MAX_PER_CYCLE)
            .toList();
    }

    private Flux<Post> listPublishedPosts() {
        Comparator<Post> newestFirst = Comparator.comparing(
            (Post post) -> post.getSpec() == null ? null : post.getSpec().getPublishTime(),
            Comparator.nullsLast(Comparator.reverseOrder()));
        return client.list(Post.class, this::isPublished, newestFirst);
    }

    private boolean isPublished(Post post) {
        if (post.getSpec() == null || post.getStatus() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(post.getSpec().getDeleted())) {
            return false;
        }
        if (!Boolean.TRUE.equals(post.getSpec().getPublish())) {
            return false;
        }
        String phase = post.getStatus().getPhase();
        return phase != null && "published".equalsIgnoreCase(phase);
    }

    private Mono<Void> push(Post post, PushSettings settings) {
        Post.PostSpec spec = post.getSpec();
        String title = truncate(spec.getTitle(), 200);
        String link = buildLink(post, settings);
        if (isBlank(title) || isBlank(link)) {
            log.warn("[blogsclub-push] 文章 {} 缺少标题或链接，跳过", post.getMetadata().getName());
            return Mono.empty();
        }
        String description = truncate(describe(post), 200);
        String date = formatDate(spec.getPublishTime());

        StringBuilder form = new StringBuilder();
        form.append("title=").append(encode(title));
        form.append("&link=").append(encode(link));
        if (!isBlank(description)) {
            form.append("&description=").append(encode(description));
        }
        if (!isBlank(date)) {
            form.append("&date=").append(encode(date));
        }
        String body = form.toString();

        long timestamp = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String canonical = "BC-PUSH-V2\n" + settings.getPushId() + "\n" + timestamp
            + "\n" + nonce + "\nPOST\n" + sha256Hex(body);
        String signature = hmacSha256Hex(canonical, settings.getPushKey());
        String authorization = "Bearer " + settings.getPushId() + "." + timestamp
            + "." + nonce + "." + signature;

        return webClient.post()
            .uri(PUSH_URL)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(20))
            .doOnNext(resp -> log.info("[blogsclub-push] 推送《{}》成功：{}", title, abbreviate(resp)))
            .doOnError(e -> log.warn("[blogsclub-push] 推送《{}》失败：{}", title, e.toString()))
            .then();
    }

    private String buildLink(Post post, PushSettings settings) {
        String base = settings.getSiteUrl();
        if (isBlank(base)) {
            URI uri = externalUrlSupplier.get();
            base = uri == null ? "" : uri.toString();
        }
        base = base.trim().replaceAll("/+$", "");

        String permalink = post.getStatus() == null ? null : post.getStatus().getPermalink();
        if (isBlank(permalink)) {
            String slug = post.getSpec() == null ? null : post.getSpec().getSlug();
            permalink = "/archives/" + (isBlank(slug) ? post.getMetadata().getName() : slug);
        }
        if (!permalink.startsWith("/")) {
            permalink = "/" + permalink;
        }
        return base + permalink;
    }

    private String describe(Post post) {
        String text = post.getStatus() == null ? null : post.getStatus().getExcerpt();
        if (isBlank(text) && post.getSpec() != null && post.getSpec().getExcerpt() != null) {
            text = post.getSpec().getExcerpt().getRaw();
        }
        if (text == null) {
            return null;
        }
        return text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private String formatDate(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    private Mono<PushRecords> loadRecords() {
        return client.fetch(ConfigMap.class, PushRecords.NAME)
            .map(PushRecords::from)
            .defaultIfEmpty(new PushRecords());
    }

    private Mono<PushRecords> saveRecords(PushRecords records) {
        return client.fetch(ConfigMap.class, PushRecords.NAME)
            .flatMap(existing -> {
                records.applyTo(existing);
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                ConfigMap created = new ConfigMap();
                Metadata metadata = new Metadata();
                metadata.setName(PushRecords.NAME);
                created.setMetadata(metadata);
                records.applyTo(created);
                return client.create(created);
            }))
            .thenReturn(records);
    }

    private String randomNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String hmacSha256Hex(String input, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ");
        return single.length() <= 300 ? single : single.substring(0, 300) + "...";
    }
}