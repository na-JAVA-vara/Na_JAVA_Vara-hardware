package com.vara.hardware.rfid.repository;

import com.vara.hardware.rfid.model.User;

import java.util.Optional;

/**
 * Port (interface) for user data access.
 * Swap implementations without touching AccessController:
 *   - InMemoryUserRepository  → unit tests / demos
 *   - JdbcUserRepository      → production (MySQL, PostgreSQL, …)
 *   - JpaUserRepository       → Spring Data JPA
 */
public interface UserRepository {

    /** Looks up a registered card holder by raw UID (case-insensitive). */
    Optional<User> findByUid(String uid);

    /** Persists a new user or updates an existing one. */
    void save(User user);

    /** Removes the user associated with the given UID. */
    void delete(String uid);
}
