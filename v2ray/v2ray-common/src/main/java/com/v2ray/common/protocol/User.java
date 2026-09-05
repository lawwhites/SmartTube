package com.v2ray.common.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * Protocol user representation (e.g. for VMess, VLESS, Socks).
 */
public class User {
    private final String id;
    private final String email;
    private final int level;

    public User(String id, String email, int level) {
        this.id = id;
        this.email = email;
        this.level = level;
    }

    public static User ofUuid(String uuidStr, String email, int level) {
        return new User(UUID.fromString(uuidStr).toString(), email, level);
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return level == user.level && Objects.equals(id, user.id) && Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email, level);
    }
}
