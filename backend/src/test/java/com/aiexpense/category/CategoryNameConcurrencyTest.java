package com.aiexpense.category;

import static com.aiexpense.category.CategoryTestSupport.create;
import static com.aiexpense.category.CategoryTestSupport.listAll;
import static com.aiexpense.category.CategoryTestSupport.update;
import static com.aiexpense.support.AuthTestSupport.json;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Concurrent requests with Turkish case variants of one name: the database index
 * uk_categories_user_active_name lets exactly one of them win (V4).
 */
@Import(AuthTestSupport.Config.class)
class CategoryNameConcurrencyTest extends DatabaseIntegrationTest {

    /** Variants that are all the same name under the Turkish-aware rule. */
    static final List<String> VARIANTS = List.of(
            "Kışlık Giyim", "KIŞLIK GİYİM", "kışlık giyim", "KİŞLİK GIYIM", "kişlik giyım", "KışLIK GiYiM");

    @Autowired
    MockMvc mvc;

    @Autowired
    DataSource dataSource;

    @Test
    void parallelCreatesWithTurkishCaseVariantsCreateExactlyOneCategory() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());
        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
        for (String name : VARIANTS) {
            requests.add(create(user.accessToken(), name, "a", "#000000"));
        }

        assertExactlyOneWins(runInParallel(requests), 201);
        assertThat(countNamed(user, "kışlık giyim")).isOne();
    }

    @Test
    void parallelRenamesToTurkishCaseVariantsSucceedOnlyOnce() throws Exception {
        // PATCH takes no application lock: only the database index prevents the duplicate.
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());
        List<String> ids = new ArrayList<>();
        for (JsonNode category : json(mvc.perform(listAll(user.accessToken())).andReturn())) {
            ids.add(category.get("id").asString());
        }
        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
        for (int i = 0; i < VARIANTS.size(); i++) {
            requests.add(update(user.accessToken(), ids.get(i), "{\"name\":\"" + VARIANTS.get(i) + "\"}"));
        }

        assertExactlyOneWins(runInParallel(requests), 200);
        assertThat(countNamed(user, "kışlık giyim")).isOne();
    }

    @Test
    void databaseRejectsAConcurrentVariantOnceTheFirstInsertCommits() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());
        UUID userId = UUID.fromString(user.userId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            insert(first, userId, "GİYİM AKSESUARI");

            // The second insert waits on the unique index until the first transaction ends.
            Future<?> second = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    insert(connection, userId, "giyim aksesuarı");
                }
                return null;
            });
            Thread.sleep(500);
            assertThat(second.isDone()).as("second insert waits for the first transaction").isFalse();

            first.commit();
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().hasMessageContaining("uk_categories_user_active_name");
        } finally {
            pool.shutdownNow();
        }
        assertThat(countNamed(user, "giyim aksesuarı")).isOne();
    }

    private List<Integer> runInParallel(List<MockHttpServletRequestBuilder> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (MockHttpServletRequestBuilder request : requests) {
                Callable<Integer> call = () -> {
                    start.await();
                    return mvc.perform(request).andReturn().getResponse().getStatus();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertExactlyOneWins(List<Integer> statuses, int success) {
        assertThat(statuses).filteredOn(s -> s == success).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != success).hasSize(statuses.size() - 1).containsOnly(409);
    }

    /** Active categories of the user whose name folds to the given lowercase name (same rule as V4). */
    private int countNamed(Tokens user, String folded) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement("""
                        SELECT count(*) FROM categories
                        WHERE user_id = ? AND archived_at IS NULL
                          AND lower(translate(name, 'ÇĞİIıÖŞÜ', 'çğiiiöşü')) = lower(translate(?, 'ÇĞİIıÖŞÜ', 'çğiiiöşü'))""")) {
            query.setObject(1, UUID.fromString(user.userId()));
            query.setString(2, folded);
            try (var rs = query.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void insert(Connection connection, UUID userId, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO categories (user_id, name, icon, color, sort_order, created_at, updated_at)
                VALUES (?, ?, 'a', '#000000', 50, ?, ?)""")) {
            Timestamp now = Timestamp.from(Instant.parse("2026-11-01T10:00:00Z"));
            insert.setObject(1, userId);
            insert.setString(2, name);
            insert.setTimestamp(3, now);
            insert.setTimestamp(4, now);
            insert.executeUpdate();
        }
    }
}
