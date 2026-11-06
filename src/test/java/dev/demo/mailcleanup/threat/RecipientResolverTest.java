package dev.demo.mailcleanup.threat;

import dev.demo.mailcleanup.config.TenantConfig;
import dev.demo.mailcleanup.model.ProviderType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientResolverTest {

    private final TenantConfig tenant = new TenantConfig("clinic", ProviderType.FAKE, 2,
            List.of("alice@clinic.example", "bob@clinic.example", "carol@clinic.example", "dave@clinic.example"),
            0, null, null, null, null, "EU", null,
            Map.of("all@clinic.example", List.of("alice@clinic.example", "team@clinic.example"),
                    "team@clinic.example", List.of("bob@clinic.example", "all@clinic.example")),
            Map.of("bob@clinic.example", "dave@clinic.example",
                    "alice@clinic.example", "alice@personal.example"),
            null);

    @Test
    void expandsNestedDistributionListsWithoutLoopingOnCycles() {
        assertThat(RecipientResolver.resolve(tenant, List.of("ALL@clinic.example")))
                .contains("alice@clinic.example", "bob@clinic.example");
    }

    @Test
    void followsForwardsInsideTheTenant() {
        assertThat(RecipientResolver.resolve(tenant, List.of("bob@clinic.example")))
                .containsExactly("bob@clinic.example", "dave@clinic.example");
    }

    @Test
    void ignoresAddressesOutsideTheTenant() {
        assertThat(RecipientResolver.resolve(tenant, List.of("alice@clinic.example", "eve@other.example")))
                .containsExactly("alice@clinic.example");
    }
}
