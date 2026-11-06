package dev.demo.mailcleanup.config;

/**
 * One relational database in the routing table (production: a MySQL/PostgreSQL DataSource).
 *
 * @param region data-residency region the database lives in, e.g. US, EU, IN, AU
 */
public record ShardConfig(String id, String region) {
    public ShardConfig {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("shard id is required");
        region = region == null || region.isBlank() ? "US" : region.trim().toUpperCase();
    }
}
