package com.rtgs.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ParticipantRegistry {
    private final Map<String, Participant> participants = new ConcurrentHashMap<>();

    public void register(Participant participant) {
        if (participants.putIfAbsent(participant.participantId(), participant) != null)
            throw new IllegalArgumentException("participant already exists: " + participant.participantId());
    }

    public Participant require(String participantId) {
        Participant participant = participants.get(participantId);
        if (participant == null) throw new IllegalArgumentException("unknown participant: " + participantId);
        return participant;
    }

    public void validate(PaymentInstruction payment) {
        require(payment.sourceParticipantId());
        require(payment.destinationParticipantId());
    }
}
