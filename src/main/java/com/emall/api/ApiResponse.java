package com.emall.api;

public record ApiResponse<T>(int code, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "ok");
    }

    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(-1, null, message);
    }
}
