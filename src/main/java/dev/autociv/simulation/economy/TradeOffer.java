package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.ResourceType;

import java.util.UUID;

public record TradeOffer(UUID settlementId, ResourceType resource, Side side,
                         double quantity, double unitPrice) {
    public enum Side { BUY, SELL }
}