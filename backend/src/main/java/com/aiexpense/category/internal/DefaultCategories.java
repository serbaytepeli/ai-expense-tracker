package com.aiexpense.category.internal;

import java.util.List;

/** Built-in categories created at registration (docs/requirements.md FR-3, approved in T1.3). */
final class DefaultCategories {

    record Definition(int sortOrder, String name, String systemKey, String icon, String color) {
    }

    static final String OTHER = "OTHER";

    static final List<Definition> ALL = List.of(
            new Definition(1, "Market", "GROCERIES", "cart-outline", "#2E7D32"),
            new Definition(2, "Yeme-İçme", "FOOD_DRINK", "restaurant-outline", "#EF6C00"),
            new Definition(3, "Ulaşım", "TRANSPORT", "bus-outline", "#1565C0"),
            new Definition(4, "Faturalar", "BILLS", "receipt-outline", "#6A1B9A"),
            new Definition(5, "Kira/Konut", "HOUSING", "home-outline", "#4E342E"),
            new Definition(6, "Sağlık", "HEALTH", "medkit-outline", "#C62828"),
            new Definition(7, "Eğlence", "ENTERTAINMENT", "film-outline", "#AD1457"),
            new Definition(8, "Giyim", "CLOTHING", "shirt-outline", "#00838F"),
            new Definition(9, "Eğitim", "EDUCATION", "school-outline", "#283593"),
            new Definition(10, "Abonelikler", "SUBSCRIPTIONS", "repeat-outline", "#F9A825"),
            new Definition(11, "Diğer", OTHER, "ellipsis-horizontal-outline", "#616161"));

    private DefaultCategories() {
    }
}
