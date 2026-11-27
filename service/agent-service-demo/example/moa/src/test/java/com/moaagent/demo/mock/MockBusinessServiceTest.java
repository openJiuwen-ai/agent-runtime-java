/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.moaagent.demo.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moaagent.agent.skill.SkillAssets;
import com.moaagent.agent.skill.SkillSchema;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Verifies the deterministic mock business operations and contracts.
 *
 * @since 0.1.3
 */
class MockBusinessServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final MockBusinessService service = new MockBusinessService(CLOCK);
    private final SkillAssets assets;

    MockBusinessServiceTest() throws Exception {
        assets = new SkillAssets("assets/tourism/skills", "travel-entry");
    }

    @Test
    void travelUsesBoundSelectionAndRequestedDates() {
        var attractions = service.execute("attractions.search", "city=北京");
        validate("destination_info", attractions);
        var plan = service.execute("trip.plan", "selection_id=" + attractions.get("selection_id")
                + "\nstart_date=2030-01-10\ndays=3\npeople=2");
        validate("plan", plan);
        assertThat(plan).containsEntry("city", "北京").containsEntry("people", 2);
        assertThat((List<?>) plan.get("itinerary")).hasSize(3);
        assertThat(plan.toString()).contains("2030-01-10", "2030-01-12");
    }

    @Test
    void quoteAndBookValidateConfirmationAndDeduplicate() {
        var search = service.execute("hotel.search",
                "city=杭州\ncheck_in=2030-01-10\ncheck_out=2030-01-13\npeople=6\nrooms=2");
        validate("hotel_search", search);
        var quote = service.execute("hotel.quote", "room_selection_id=" + search.get("room_selection_id"));
        validate("hotel_quote", quote);
        assertThat(quote).containsEntry("total_cents", 238800L);
        String booking = "quote_id=" + quote.get("quote_id") + "\nconfirmation_text=" + quote.get("confirmation_text");
        assertThatThrownBy(() -> service.execute("hotel.book", "quote_id=" + quote.get("quote_id")
                + "\nconfirmation_text=篡改报价")).isInstanceOf(IllegalArgumentException.class);
        var receipt = service.execute("hotel.book", booking);
        validate("hotel_book", receipt);
        assertThat(service.execute("hotel.book", booking)).isEqualTo(receipt);
        assertThat(receipt).containsEntry("simulated", true);
    }

    @Test
    void rejectsInvalidFieldsDatesAndIdentifiers() {
        for (String query : List.of("city=杭州\ncity=北京", "city=杭州\nunknown=x", "city=成都", "city=杭州\n")) {
            assertThatThrownBy(() -> service.execute("attractions.search", query))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.execute("hotel.search",
                "city=杭州\ncheck_in=2030-01-10\ncheck_out=2030-01-10\npeople=2\nrooms=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute("hotel.quote", "room_selection_id=missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute("unknown", "city=杭州")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attractionsQuery("查询成都景点")).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private void validate(String name, Map<String, Object> result) {
        SkillSchema.validate((Map<String, Object>) assets.contract("contracts/" + name + ".yml").get("result"), result);
    }
}
