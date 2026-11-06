package dev.demo.mailcleanup.storage;

/** One relational database in the routing table. The demo keeps its "tables" in memory. */
public record Shard(String id, String region, ThreatStateStore state, AuditLog audit) { }
