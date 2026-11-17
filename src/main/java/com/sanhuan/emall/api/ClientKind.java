package com.sanhuan.emall.api;

public enum ClientKind {
    C("c"),
    MER("mer"),
    PICKUP("pickup"),
    PLATFORM("platform"),
    MERCHANT("merchant");

    private final String path;

    ClientKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static ClientKind from(String path) {
        for (ClientKind kind : values()) {
            if (kind.path.equals(path)) {
                return kind;
            }
        }
        return null;
    }
}
