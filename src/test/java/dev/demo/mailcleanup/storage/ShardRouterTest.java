package dev.demo.mailcleanup.storage;

import dev.demo.mailcleanup.config.DemoProperties;
import dev.demo.mailcleanup.config.ShardConfig;
import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.config.TenantRegistry;
import dev.demo.mailcleanup.model.ProviderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardRouterTest {

    private static final List<ShardConfig> SHARDS =
            List.of(new ShardConfig("DB1", "US"), new ShardConfig("DB2", "EU"), new ShardConfig("DB3", "EU"));

    private static TenantConfig tenant(String id, String region, String shard) {
        return new TenantConfig(id, ProviderType.FAKE, 2, List.of(), 0, null, null, null, null, region, shard,
                null, null, null);
    }

    private static ShardRouter router(TenantConfig... tenants) {
        DemoProperties props = new DemoProperties(4, 100, 0, 3, List.of(tenants), SHARDS, null);
        return new ShardRouter(props, new TenantRegistry(props));
    }

    @Test
    void routesCustomerToFirstShardInItsRegion() {
        assertThat(router(tenant("acme", "EU", null)).forTenant("acme").id()).isEqualTo("DB2");
    }

    @Test
    void honoursExplicitShard() {
        assertThat(router(tenant("acme", "EU", "DB3")).forTenant("acme").id()).isEqualTo("DB3");
    }

    @Test
    void refusesShardOutsideCustomerRegion() {
        assertThatThrownBy(() -> router(tenant("acme", "EU", "DB1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("data residency");
    }

    @Test
    void customersOnDifferentShardsDoNotShareState() {
        ShardRouter r = router(tenant("us-co", "US", null), tenant("eu-co", "EU", null));
        assertThat(r.forTenant("us-co").state()).isNotSameAs(r.forTenant("eu-co").state());
    }
}
