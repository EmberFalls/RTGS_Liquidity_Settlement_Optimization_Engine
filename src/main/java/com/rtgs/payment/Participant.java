package com.rtgs.payment;

public record Participant(String participantId, String displayName) {
    public Participant {
        if (participantId == null || participantId.isBlank()) throw new IllegalArgumentException("participantId is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    }
}
