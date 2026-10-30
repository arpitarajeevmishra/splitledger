package com.splitledger.demo;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.application.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Lets a visitor in without signing up (SL-904).
 *
 * <p><b>It logs in. It does not mint a session.</b> The obvious implementation is a method
 * on {@code AuthService} that issues a session for a given user id, and that method would
 * then exist -- a way to obtain a session for an arbitrary account, one refactor away from
 * being reachable with an id from a request. Logging in with the demo account's own
 * password reuses the path that already has every guard on it, and adds no new trust
 * anywhere.
 *
 * <p>The password never leaves this process: it is configuration, the same value that
 * seeded the account, and the browser receives only an ordinary session.
 *
 * <p>Conditional on that password being configured, so a deployment that has not seeded a
 * demo does not carry the endpoint at all. {@code @ConditionalOnExpression} rather than
 * {@code @ConditionalOnProperty} because an empty environment variable is the empty string
 * and not absence -- the third time that distinction has mattered here (D-115, SL-701,
 * SL-705).
 */
@Service
@ConditionalOnExpression("'${app.demo.seed-password:}'.trim() != ''")
public class DemoTourService {

    private static final Logger log = LoggerFactory.getLogger(DemoTourService.class);

    private final AuthService auth;
    private final String password;

    DemoTourService(AuthService auth, @Value("${app.demo.seed-password:}") String password) {
        this.auth = auth;
        this.password = password;
    }

    /**
     * @throws ApiException when the demo account is not there, which is an operator
     *     problem rather than a visitor's: the password is configured, so seeding was
     *     meant to have happened. Logged as such, and answered as "not available" rather
     *     than as a credential failure, because the visitor supplied no credential.
     */
    public AuthService.Session start() {
        try {
            return auth.login(DemoSeed.OWNER_EMAIL, password);
        } catch (ApiException failure) {
            log.error("The demo tour is configured but {} cannot sign in: has the seed run?",
                    DemoSeed.OWNER_EMAIL);
            throw new ApiException(ErrorCode.NOT_FOUND, "The demo tour is not available");
        }
    }
}
