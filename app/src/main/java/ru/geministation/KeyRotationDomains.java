package ru.geministation;

import java.util.EnumMap;
import java.util.function.Consumer;

/** The encrypted key list is shared, but each API has its own in-memory preference. */
final class KeyRotationDomains {
    enum Domain { TEXT, TTS, LIVE, YOUTUBE }
    private final EnumMap<Domain, KeyRotation> rotations = new EnumMap<>(Domain.class);
    KeyRotationDomains(Consumer<Domain> onSwitch) {
        for (Domain domain : Domain.values()) rotations.put(domain, new KeyRotation(() -> onSwitch.accept(domain)));
    }
    KeyRotation rotation(Domain domain) {
        if (domain == null) throw new IllegalArgumentException("Key rotation domain is required");
        return rotations.get(domain);
    }
}
