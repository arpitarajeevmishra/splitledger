package com.splitledger.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code users} table from V1__init.sql. Hibernate runs with
 * {@code ddl-auto: validate}, so a drift between this class and the migration fails
 * the integration test rather than silently corrupting a column.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Always stored lower-cased: the unique index is exact, so normalisation is ours to do. */
    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "upi_id", length = 100)
    private String upiId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    private User(UUID id, String email, String name, String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public static User register(String email, String name, String passwordHash, Instant now) {
        return new User(UUID.randomUUID(), normaliseEmail(email), name, passwordHash, now);
    }

    public static String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String name() {
        return name;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String upiId() {
        return upiId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changeUpiId(String newUpiId) {
        this.upiId = newUpiId;
    }
}
