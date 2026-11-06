package dev.demo.mailcleanup.model;

/** A message found in a mailbox's inbox. */
public record FoundMessage(String id, String from, String subject) { }
