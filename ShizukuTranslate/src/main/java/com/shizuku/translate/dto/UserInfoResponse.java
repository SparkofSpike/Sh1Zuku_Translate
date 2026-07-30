package com.shizuku.translate.dto;

public class UserInfoResponse {
    private String username;
    private boolean isAdmin;

    public UserInfoResponse(String username, boolean isAdmin) {
        this.username = username;
        this.isAdmin = isAdmin;
    }
    public String getUsername() { return username; }
    public boolean getIsAdmin() { return isAdmin; }
}
