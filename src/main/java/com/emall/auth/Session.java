package com.emall.auth;

import com.emall.api.ClientKind;

public record Session(
        String jti,
        long accountId,
        String username,
        String realName,
        String avatar,
        ClientKind client
) {
    public static final String ATTR = "emall.session";
}
