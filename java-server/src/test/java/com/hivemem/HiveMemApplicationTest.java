package com.hivemem;

import com.hivemem.attachment.GeocodingService;
import com.hivemem.attachment.ImageMetaBackfillRunner;
import com.hivemem.auth.TokenService;
import com.hivemem.embedding.EmbeddingClient;
import com.hivemem.embedding.EmbeddingMigrationService;
import com.hivemem.embedding.FixedEmbeddingClient;
import com.hivemem.popularity.PopularityRefreshScheduler;
import com.hivemem.sync.InstanceConfig;
import com.hivemem.sync.PullScheduler;
import com.hivemem.sync.PushDispatcher;
import com.hivemem.sync.SyncOpsRepository;
import com.hivemem.sync.SyncPeerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.main.lazy-initialization=true",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration"
})
@Import(HiveMemApplicationTest.TestConfig.class)
class HiveMemApplicationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        EmbeddingClient testEmbeddingClient() {
            return new FixedEmbeddingClient();
        }
    }

    @MockitoBean(name = "dbTokenService")
    private TokenService tokenService;

    @MockitoBean
    private EmbeddingMigrationService embeddingMigrationService;

    @MockitoBean
    private PopularityRefreshScheduler popularityRefreshScheduler;

    @MockitoBean
    private PullScheduler pullScheduler;

    @MockitoBean
    private PushDispatcher pushDispatcher;

    @MockitoBean
    private SyncPeerRepository syncPeerRepository;

    @MockitoBean
    private SyncOpsRepository syncOpsRepository;

    @MockitoBean
    private InstanceConfig instanceConfig;

    @MockitoBean
    private ImageMetaBackfillRunner imageMetaBackfillRunner;

    @MockitoBean
    private GeocodingService geocodingService;

    @Test
    void contextLoads() {
    }
}
