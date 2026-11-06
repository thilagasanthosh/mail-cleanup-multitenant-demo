package dev.demo.mailcleanup.model;

/** What identifies the malicious email. Production would also use Internet-Message-ID and hashes. */
public record SearchCriteria(String sender, String subjectContains) {

    public boolean isEmpty() {
        return isBlank(sender) && isBlank(subjectContains);
    }

    public boolean matches(String from, String subject) {
        boolean senderOk = isBlank(sender) || (from != null && from.equalsIgnoreCase(sender.trim()));
        boolean subjectOk = isBlank(subjectContains)
                || (subject != null && subject.toLowerCase().contains(subjectContains.trim().toLowerCase()));
        return senderOk && subjectOk;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
