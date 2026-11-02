package com.aiexpense.user;

import java.util.Optional;
import java.util.UUID;

/** Public API of the user module (docs/architecture.md section 3.1). */
public interface UserApi {

    /** Profile data that may be returned to the user it belongs to. */
    record UserView(UUID id, String email, String displayName, String currency, String timeZone, String locale) {
    }

    /** Only for authentication: never return the hash to a client or write it to a log. */
    record UserCredentials(UUID userId, String passwordHash) {
        @Override
        public String toString() {
            return "UserCredentials[userId=" + userId + ", passwordHash=***]";
        }
    }

    /**
     * Creates a user. {@code email} must already be normalized ({@link EmailAddresses}) and
     * {@code passwordHash} must be a BCrypt hash. An unknown or empty time zone becomes
     * Europe/Istanbul; the currency is always TRY (D-3).
     *
     * @throws com.aiexpense.common.ApiException 409 EMAIL_TAKEN if the address is registered
     */
    UserView create(String email, String passwordHash, String displayName, String timeZone);

    Optional<UserCredentials> findCredentials(String normalizedEmail);

    Optional<UserView> findById(UUID userId);
}
