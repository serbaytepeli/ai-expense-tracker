package com.aiexpense.expense;

import static com.aiexpense.expense.ExpenseTestSupport.body;
import static com.aiexpense.expense.ExpenseTestSupport.categoryId;
import static com.aiexpense.expense.ExpenseTestSupport.create;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "The date cannot be after today in the user's time zone" (D-7, T2.1 AC), checked in the service
 * layer with a fixed Clock. Each user registers at the instant under test, so tokens stay valid.
 */
@Import(AuthTestSupport.Config.class)
class ExpenseDateRuleTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Test
    void istanbulDayChangesAt21Utc() throws Exception {
        // Istanbul is UTC+3: 20:59 UTC is 23:59 on Nov 1, 21:00 UTC is 00:00 on Nov 2.
        at("2026-11-01T20:59:00Z", "Europe/Istanbul")
                .accepts("2026-11-01").rejects("2026-11-02");
        at("2026-11-01T21:00:00Z", "Europe/Istanbul")
                .accepts("2026-11-02").rejects("2026-11-03");
    }

    @Test
    void theSameInstantIsADifferentDayInDifferentTimeZones() throws Exception {
        String instant = "2026-11-01T10:30:00Z";
        // UTC+14: already Nov 2.
        at(instant, "Pacific/Kiritimati").accepts("2026-11-02").rejects("2026-11-03");
        // UTC-11: still Oct 31.
        at(instant, "Pacific/Pago_Pago").accepts("2026-10-31").rejects("2026-11-01");
        // UTC+3: Nov 1.
        at(instant, "Europe/Istanbul").accepts("2026-11-01").rejects("2026-11-02");
    }

    @Test
    void monthEndMidnight() throws Exception {
        at("2026-11-30T20:59:59Z", "Europe/Istanbul").accepts("2026-11-30").rejects("2026-12-01");
        at("2026-11-30T21:00:00Z", "Europe/Istanbul").accepts("2026-12-01");
    }

    @Test
    void yearEndMidnight() throws Exception {
        at("2026-12-31T20:59:59Z", "Europe/Istanbul").accepts("2026-12-31").rejects("2027-01-01");
        at("2026-12-31T21:00:00Z", "Europe/Istanbul").accepts("2027-01-01").rejects("2027-01-02");
        // Pago Pago is still in the old year long after Istanbul has moved on.
        at("2027-01-01T10:59:59Z", "Pacific/Pago_Pago").accepts("2026-12-31").rejects("2027-01-01");
        at("2027-01-01T11:00:00Z", "Pacific/Pago_Pago").accepts("2027-01-01");
    }

    @Test
    void theRuleAlsoAppliesToUpdates() throws Exception {
        Check check = at("2026-11-01T20:59:00Z", "Europe/Istanbul");
        String id = AuthTestSupport.json(mvc.perform(create(check.token, body("1", "2026-11-01", check.category)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mvc.perform(ExpenseTestSupport.replace(check.token, id, body("1", "2026-11-02", check.category)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("date")));
        clock.set(Instant.parse("2026-11-01T21:00:00Z"));
        mvc.perform(ExpenseTestSupport.replace(check.token, id, body("1", "2026-11-02", check.category)))
                .andExpect(status().isOk());
    }

    private Check at(String instant, String timeZone) throws Exception {
        clock.set(Instant.parse(instant));
        Tokens user = ExpenseTestSupport.register(mvc, timeZone);
        return new Check(user.accessToken(), categoryId(mvc, user.accessToken(), "OTHER"));
    }

    private final class Check {
        final String token;
        final String category;

        Check(String token, String category) {
            this.token = token;
            this.category = category;
        }

        Check accepts(String date) throws Exception {
            mvc.perform(create(token, body("1", date, category))).andExpect(status().isCreated());
            return this;
        }

        Check rejects(String date) throws Exception {
            mvc.perform(create(token, body("1", date, category)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("date"))
                    .andExpect(jsonPath("$.errors[0].message").value("must not be in the future"));
            return this;
        }
    }
}
