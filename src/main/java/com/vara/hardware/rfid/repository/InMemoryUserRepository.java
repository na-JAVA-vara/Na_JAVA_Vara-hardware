package com.vara.hardware.rfid.repository;

import com.vara.hardware.rfid.model.User;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory stub implementation.
 *
 * Replace with a JDBC or JPA implementation for production use.
 * UIDs are stored and looked up in upper-case so "a1b2c3d4" and "A1B2C3D4" match.
 */
public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentHashMap<String, User> store = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findByUid(String uid) {
        return Optional.ofNullable(store.get(uid.toUpperCase()));
    }

    @Override
    public void save(User user) {
        store.put(user.getUid().toUpperCase(), user);
    }

    @Override
    public void delete(String uid) {
        store.remove(uid.toUpperCase());
    }
}
