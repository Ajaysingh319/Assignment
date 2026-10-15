package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.orders.dto.CreateOrderItemRequest;
import com.assignment.fulfilment.orders.dto.CreateOrderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CanonicalPayloadHasher {

    private CanonicalPayloadHasher() {
    }

    public static String computeHash(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        String currency = request.currency() != null && !request.currency().isBlank()
                ? request.currency().trim().toUpperCase()
                : "USD";

        List<String> canonicalItems = new ArrayList<>();
        if (request.items() != null) {
            List<CreateOrderItemRequest> sorted = new ArrayList<>(request.items());
            sorted.sort(Comparator
                    .comparing((CreateOrderItemRequest item) -> item.productId() != null ? item.productId().toString() : "")
                    .thenComparing(item -> item.quantity() != null ? item.quantity() : 0L)
                    .thenComparing(item -> item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO)
            );

            for (CreateOrderItemRequest item : sorted) {
                String productId = item.productId() != null ? item.productId().toString() : "";
                long quantity = item.quantity() != null ? item.quantity() : 0L;
                String price = item.unitPrice() != null
                        ? item.unitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()
                        : "0.00";
                canonicalItems.add(productId + ":" + quantity + ":" + price);
            }
        }

        String canonicalString = currency + "|" + String.join(",", canonicalItems);
        return sha256Hex(canonicalString);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
