package com.shizuku.translate.dto;

import java.util.List;

/** Body of {@code POST /api/v1/feedback/rate}: one anonymous, one-click rating. */
public class FeedbackRateRequest {
    private String requestId;
    private Integer rating;
    private List<String> tags;
    private String comment;
    private String deviceFp;
    private String permalink;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getDeviceFp() { return deviceFp; }
    public void setDeviceFp(String deviceFp) { this.deviceFp = deviceFp; }
    public String getPermalink() { return permalink; }
    public void setPermalink(String permalink) { this.permalink = permalink; }
}
