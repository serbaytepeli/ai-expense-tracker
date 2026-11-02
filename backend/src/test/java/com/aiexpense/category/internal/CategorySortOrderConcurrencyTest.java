package com.aiexpense.category.internal;

import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import javax.sql.DataSource;

/** Concurrent category creation for the same user: every new category gets its own sortOrder. */
@Import(AuthTestSupport.Config.class)
class CategorySortOrderConcurrencyTest extends DatabaseIntegrationTest {

    static final int PARALLEL = 8;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void parallelCreatesGetDistinctIncreasingSortOrders() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < PARALLEL; i++) {
                String name = "Paralel " + i;
                Callable<Integer> call = () -> {
                    start.await();
                    MvcResult result = mvc.perform(post("/api/v1/categories")
                            .header("Authorization", "Bearer " + user.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\",\"icon\":\"a\",\"color\":\"#000000\"}"))
                            .andReturn();
                    return result.getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> sortOrders = jdbc.sql("""
                SELECT sort_order FROM categories WHERE user_id = :u AND system_key IS NULL ORDER BY sort_order""")
                .param("u", UUID.fromString(user.userId())).query(Integer.class).list();
        assertThat(sortOrders).containsExactly(12, 13, 14, 15, 16, 17, 18, 19);
    }

    @Test
    void createWaitsWhileAnotherTransactionHoldsTheUsersSortOrderLock() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
                lock.setInt(1, CategoryService.SORT_ORDER_LOCK_CLASS);
                lock.setString(2, user.userId());
                lock.execute();
            }

            Future<Integer> create = pool.submit(() -> mvc.perform(post("/api/v1/categories")
                    .header("Authorization", "Bearer " + user.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Bekleyen\",\"icon\":\"a\",\"color\":\"#000000\"}"))
                    .andReturn().getResponse().getStatus());

            Thread.sleep(500);
            assertThat(create.isDone()).as("create must wait for the lock").isFalse();

            other.rollback(); // releases the transaction-scoped lock
            assertThat(create.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
    }
}
