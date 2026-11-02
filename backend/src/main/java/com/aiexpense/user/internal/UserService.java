package com.aiexpense.user.internal;

import com.aiexpense.common.ApiException;
import com.aiexpense.user.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserService implements UserApi {

    static final String DEFAULT_TIME_ZONE = "Europe/Istanbul";
    static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

    private final UserRepository users;
    private final Clock clock;

    UserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    // Joins the caller's transaction (registration creates the user and the session together).
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UserView create(String email, String passwordHash, String displayName, String timeZone) {
        Instant now = clock.instant();
        UserEntity user = UserEntity.create(UUID.randomUUID(), email, passwordHash, displayName,
                resolveTimeZone(timeZone), now);
        try {
            // flush now so a duplicate e-mail is detected here, also for two concurrent requests.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (mentions(e, EMAIL_UNIQUE_CONSTRAINT)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "This e-mail address is already registered");
            }
            throw e;
        }
        return toView(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentials(String normalizedEmail) {
        return users.findByEmail(normalizedEmail).map(user -> new UserCredentials(user.getId(), user.passwordHash()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findById(UUID userId) {
        return users.findById(userId).map(UserService::toView);
    }

    /** IANA region IDs only (for example Europe/Istanbul); anything else falls back (D-4). */
    static String resolveTimeZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return DEFAULT_TIME_ZONE;
        }
        String candidate = timeZone.strip();
        return ZoneId.getAvailableZoneIds().contains(candidate) ? candidate : DEFAULT_TIME_ZONE;
    }

    private static boolean mentions(DataIntegrityViolationException e, String constraint) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null && cause.getMessage().contains(constraint);
    }

    private static UserView toView(UserEntity user) {
        return new UserView(user.getId(), user.email(), user.displayName(), user.currency(), user.timeZone(),
                user.locale());
    }
}
