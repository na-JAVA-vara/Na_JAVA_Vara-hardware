package com.vara.hardware.rfid.model;

/**
 * Domain entity representing a registered card holder.
 * In production, map this to a JPA @Entity or a JDBC RowMapper.
 */
public final class User {

    private final String  uid;
    private final String  name;
    private final boolean active;

    public User(String uid, String name, boolean active) {
        this.uid    = uid;
        this.name   = name;
        this.active = active;
    }

    public String  getUid()    { return uid; }
    public String  getName()   { return name; }
    public boolean isActive()  { return active; }

    @Override
    public String toString() {
        return "User{uid='" + uid + "', name='" + name + "', active=" + active + '}';
    }
}
