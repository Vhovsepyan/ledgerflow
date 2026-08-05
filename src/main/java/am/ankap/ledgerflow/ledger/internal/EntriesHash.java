package am.ankap.ledgerflow.ledger.internal;

import am.ankap.ledgerflow.ledger.EntryLine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

final class EntriesHash {

    private EntriesHash() {
    }

    static String of(List<EntryLine> entries) {
        String canonical = entries.stream()
                .map(entry -> "%s:%s:%d".formatted(
                        entry.accountKey(),
                        entry.amount().currency().getCurrencyCode(),
                        entry.amount().minorUnits()))
                .sorted()
                .collect(Collectors.joining("|"));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}