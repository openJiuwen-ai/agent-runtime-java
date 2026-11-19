/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fictional data; identifiers bind later operations to validated results.
 *
 * @since 0.1.3
 */
@Service
@Profile("mock")
public class MockBusinessService {
    private static final Logger LOG = LoggerFactory.getLogger(MockBusinessService.class);

    private final Clock clock;
    private final Map<String, Saved> state = new LinkedHashMap<>();

    /**
     * Creates a mock service using the system UTC clock.
     */
    public MockBusinessService() {
        this(Clock.systemUTC());
    }

    MockBusinessService(Clock clock) {
        this.clock = clock;
    }

    synchronized Map<String, Object> execute(String intent, String query) {
        Map<String, String> fields = fields(query);
        Map<String, Object> result = switch (intent) {
            case "attractions.search" -> attractions(fields);
            case "trip.plan" -> plan(fields);
            case "hotel.search" -> search(fields);
            case "hotel.quote" -> quote(fields);
            case "hotel.book" -> book(fields);
            default -> throw new IllegalArgumentException("Unknown demo intent: " + intent);
        };
        LOG.info("[DEMO-MOCK] intent={} action=completed", intent);
        return result;
    }

    Map<String, Object> attractionsQuery(String query) {
        require(query.contains("景点") || query.contains("景区"), "Only demo attraction queries are supported");
        String city = List.of("杭州", "北京").stream().filter(query::contains).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Supported demo cities: 杭州、北京"));
        return execute("attractions.search", "city=" + city);
    }

    private Map<String, Object> attractions(Map<String, String> fields) {
        exact(fields, "city");
        String city = city(fields);
        var spots = List.of(Map.of("id", "lake", "name", city + "演示湖"),
                Map.of("id", "museum", "name", city + "演示馆"));
        String id = save("selection", Map.of("city", city));
        return Map.of("selection_id", id, "city", city, "attractions", spots, "simulated", true);
    }

    private Map<String, Object> plan(Map<String, String> fields) {
        exact(fields, "selection_id", "start_date", "days", "people");
        Map<String, Object> selection = load(fields.get("selection_id"), "selection");
        LocalDate start = future(fields.get("start_date"));
        int days = number(fields, "days", 1, 14);
        int people = number(fields, "people", 1, 10);
        if (!(selection.get("city") instanceof String city)) {
            throw new IllegalStateException("Selection city must be text");
        }
        List<Map<String, Object>> itinerary = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            itinerary.add(Map.of("date", start.plusDays(index).toString(),
                    "activity", "参观" + city + (index % 2 == 0 ? "演示湖" : "演示馆")));
        }
        return Map.of("city", city, "people", people, "itinerary", itinerary, "simulated", true);
    }

    private Map<String, Object> search(Map<String, String> fields) {
        exact(fields, "city", "check_in", "check_out", "people", "rooms");
        String city = city(fields);
        LocalDate checkIn = future(fields.get("check_in"));
        LocalDate checkOut = LocalDate.parse(fields.get("check_out"));
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        require(nights > 0 && nights <= 14, "Stay must be 1 to 14 nights");
        int people = number(fields, "people", 1, 10);
        int rooms = number(fields, "rooms", 1, 5);
        int capacity = (people + rooms - 1) / rooms;
        require(capacity <= 3, "No demo room can accommodate these guests");
        List<Map<String, Object>> options = new ArrayList<>();
        if (capacity <= 2) {
            options.add(Map.of("room_id", "standard", "name", "演示标准间",
                    "capacity", 2, "nightly_price_cents", 26800));
        }
        options.add(Map.of("room_id", "family", "name", "演示家庭房", "capacity", 3,
                "nightly_price_cents", 39800));
        var selected = options.get(0);
        Map<String, Object> trusted = Map.of("city", city, "check_in", checkIn.toString(),
                "check_out", checkOut.toString(), "people", people, "rooms", rooms,
                "nights", nights, "room_name", selected.get("name"),
                "nightly_price_cents", selected.get("nightly_price_cents"));
        String id = save("rooms", trusted);
        return Map.of("room_selection_id", id, "city", city, "check_in", checkIn.toString(),
                "check_out", checkOut.toString(), "people", people, "room_count", rooms,
                "rooms", options, "simulated", true);
    }

    private Map<String, Object> quote(Map<String, String> fields) {
        exact(fields, "room_selection_id");
        Map<String, Object> selection = load(fields.get("room_selection_id"), "rooms");
        if (!(selection.get("nightly_price_cents") instanceof Number price)
                || !(selection.get("nights") instanceof Number nights)
                || !(selection.get("rooms") instanceof Number rooms)) {
            throw new IllegalStateException("Room selection contains invalid numeric values");
        }
        long total = price.longValue() * nights.longValue() * rooms.longValue();
        String text = "模拟预订" + selection.get("city") + "演示酒店 / " + selection.get("room_name")
                + "，" + selection.get("check_in") + "入住，" + selection.get("check_out") + "退房，"
                + selection.get("people") + "位成人，" + selection.get("rooms") + "间房，演示总价"
                + java.math.BigDecimal.valueOf(total, 2).toPlainString() + "元。仅用于演示，不产生真实订单。";
        String id = save("quote", Map.of("confirmation_text", text, "total_cents", total));
        return Map.of("quote_id", id, "confirmation_text", text, "total_cents", total, "simulated", true);
    }

    private Map<String, Object> book(Map<String, String> fields) {
        exact(fields, "quote_id", "confirmation_text");
        Map<String, Object> quote = load(fields.get("quote_id"), "quote");
        require(quote.get("confirmation_text").equals(fields.get("confirmation_text")),
                "Confirmation must match the current quote");
        String key = "booking:" + fields.get("quote_id");
        Saved existing = state.get(key);
        if (existing != null) {
            return existing.value();
        }
        Map<String, Object> receipt = Map.of("booking_id", "demo-" + UUID.randomUUID(), "simulated", true);
        state.put(key, new Saved("booking", receipt));
        return receipt;
    }

    private String save(String kind, Map<String, Object> value) {
        String id = kind + "-" + UUID.randomUUID();
        state.put(id, new Saved(kind, value));
        return id;
    }

    private Map<String, Object> load(String id, String kind) {
        Saved saved = state.get(id);
        require(saved != null && saved.kind().equals(kind), "Missing or invalid " + kind + " identifier");
        return saved.value();
    }

    private static Map<String, String> fields(String query) {
        require(query != null && query.length() <= 4096, "Invalid query size");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : query.split("\\R", -1)) {
            int separator = line.indexOf('=');
            require(separator > 0, "Expected one key=value per line");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            require(!value.isBlank() && fields.putIfAbsent(key, value) == null, "Empty or duplicate field");
        }
        return fields;
    }

    private static void exact(Map<String, String> fields, String... names) {
        require(fields.keySet().equals(Set.of(names)), "Missing or unknown operation fields");
    }

    private static String city(Map<String, String> fields) {
        String city = fields.get("city");
        require(Set.of("杭州", "北京").contains(city), "Supported demo cities: 杭州、北京");
        return city;
    }

    private LocalDate future(String text) {
        LocalDate date = LocalDate.parse(text);
        require(!date.isBefore(LocalDate.now(clock)), "Use a current or future date");
        return date;
    }

    private static int number(Map<String, String> fields, String key, int min, int max) {
        String text = fields.get(key);
        require(text.matches("[1-9][0-9]?"), "Expected a positive integer");
        int value = Integer.parseInt(text);
        require(value >= min && value <= max, "Number outside demo range: " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record Saved(String kind, Map<String, Object> value) { }
}
