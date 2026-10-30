package com.fleetops.security;

/** Application roles. Ids match the rows inserted into {@code roles} by V1. */
public enum Role {
    ADMIN(1, "Admin"),
    DISPATCHER(2, "Dispatcher"),
    DRIVER(3, "Driver");

    private final short id;
    private final String label;

    Role(int id, String label) {
        this.id = (short) id;
        this.label = label;
    }

    public short id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Role fromId(short id) {
        for (Role role : values()) {
            if (role.id == id) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role id " + id);
    }
}
