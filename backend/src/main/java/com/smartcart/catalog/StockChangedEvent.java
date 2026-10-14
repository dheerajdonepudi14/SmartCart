package com.smartcart.catalog;

import java.util.List;

/** Published inside a transaction whenever stock moves; pushed to shoppers only after commit. */
public record StockChangedEvent(List<StockLevel> items) {
    public record StockLevel(Long productId, int stock) {}

    public static StockChangedEvent of(List<Product> products) {
        return new StockChangedEvent(products.stream().map(p -> new StockLevel(p.getId(), p.getStock())).toList());
    }
}
