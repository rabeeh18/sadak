package com.fleetops.security;

/** Application roles. Ids match the rows in {@code roles} (V1; Admin, id 1, was removed in V7). */
public enum Role {
    DISPATCHER(2, "Dispatcher"),
    DRIVER(3, "Driver"),
    WAREHOUSE(4, "Warehouse");

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
