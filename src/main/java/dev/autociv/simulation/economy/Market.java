package dev.autociv.simulation.economy;

import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;

import java.util.ArrayList;
import java.util.List;

/** Daily market offers derived from a city's stockpile targets. */
public final class Market {

    private final Settlement settlement;
    private final PriceSystem prices;

    public Market(Settlement settlement, PriceSystem prices) {
        this.settlement = settlement;
        this.prices = prices;
    }

    public double price(ResourceType resource) {
        return prices.quote(settlement, resource);
    }

    public List<TradeOffer> offers() {
        List<TradeOffer> offers = new ArrayList<>();
        for (ResourceType resource : ResourceType.all().values()) {
            if (resource.equals(ResourceType.MONEY)) {
                continue;
            }
            double target = prices.targetStock(settlement, resource);
            double stock = settlement.stockpile().get(resource);
            double batch = Math.max(25.0, target * 0.25);
            if (stock > target * 1.1) {
                offers.add(new TradeOffer(settlement.id(), resource, TradeOffer.Side.SELL,
                        Math.min(stock - target, batch), price(resource)));
            } else if (stock < target * 0.9) {
                offers.add(new TradeOffer(settlement.id(), resource, TradeOffer.Side.BUY,
                        Math.min(target - stock, batch), price(resource)));
            }
        }
        return List.copyOf(offers);
    }
}