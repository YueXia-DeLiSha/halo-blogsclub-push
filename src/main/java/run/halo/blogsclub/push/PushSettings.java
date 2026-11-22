package run.halo.blogsclub.push;

/**
 * 插件设置项，对应 settings.yaml 中 group 为 basic 的表单。
 */
public class PushSettings {

    /**
     * 是否开启推送。
     */
    private Boolean status;

    /**
     * 推送 ID。
     */
    private String pushId;

    /**
     * 推送 KEY，仅用于本地签名，不会随请求发送。
     */
    private String pushKey;

    /**
     * 首次同步的历史文章数量，0 表示只推送之后的新文章。
     */
    private Integer posts;

    /**
     * 文章链接域名，留空则使用 Halo 的外部访问地址。
     */
    private String siteUrl;

    public Boolean getStatus() {
        return status;
    }

    public void setStatus(Boolean status) {
        this.status = status;
    }

    public String getPushId() {
        return pushId;
    }

    public void setPushId(String pushId) {
        this.pushId = pushId;
    }

    public String getPushKey() {
        return pushKey;
    }

    public void setPushKey(String pushKey) {
        this.pushKey = pushKey;
    }

    public Integer getPosts() {
        return posts;
    }

    public void setPosts(Integer posts) {
        this.posts = posts;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }
}
