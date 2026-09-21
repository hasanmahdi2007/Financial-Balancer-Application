package com.hasan.budget.costofliving.persistence;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cascading location picker over HTTP, against the real seeded database.
 *
 * <p>An integration test rather than a unit one because the things that can actually be wrong here
 * are the route, the serialised shape, and whether the figures a browser receives are the ones in
 * the migration. None of those can be established by calling the controller as an object.
 *
 * <p>The assertion that matters most is the last one in each case: no constant name may appear in a
 * payload. {@code CROWDSOURCED} is an internal word, and a user asked to judge a figure labelled
 * with it is being asked to guess.
 */
// Filters off: budget-core publishes no port and trusts a user-id header the gateway injects, so
// who may call this is the gateway's question and packet P6's to answer. Leaving the default
// security chain in would make this test assert an authentication policy that does not exist yet,
// and it would start passing or failing for reasons that have nothing to do with the catalogue.
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class CatalogueEndpointIT extends CostOfLivingDatabaseFixture {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the country list offers only countries with figures behind them")
    void theCountryListIsOfferedWithItsCaveats() throws Exception {
        mockMvc.perform(get("/api/catalogue/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(hasItems("LB", "US")))
                .andExpect(jsonPath("$[?(@.code == 'LB')].name").value("Lebanon"))
                // Lebanon being dollar-priced in practice is something a person needs to know
                // before they trust a figure quoted in dollars, so it travels with the country.
                .andExpect(jsonPath("$[?(@.code == 'LB')].note")
                        .value(everyItem(containsString("dollars"))));
    }

    @Test
    @DisplayName("choosing a country returns its cities, each saying what its figures are")
    void citiesArriveWithProvenanceInPlainWords() throws Exception {
        mockMvc.perform(get("/api/catalogue/countries/LB/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItems("beirut", "tripoli-lb")))
                .andExpect(jsonPath("$[0].name").value("Beirut"))
                .andExpect(jsonPath("$[0].basis").value("Researched by us"))
                .andExpect(jsonPath("$[0].explanation").value(containsString("not an official")))
                .andExpect(jsonPath("$[0].gathered").value("2026-09-01"))
                .andExpect(content().string(not(containsString("CROWDSOURCED"))));
    }

    @Test
    @DisplayName("a country's cities are its own")
    void oneCountrysCitiesNeverLeakIntoAnothers() throws Exception {
        mockMvc.perform(get("/api/catalogue/countries/US/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItems("wichita", "new-york")))
                .andExpect(jsonPath("$[*].id").value(not(hasItem("beirut"))));
    }

    @Test
    @DisplayName("the manual form arrives already filled in, and explains every box")
    void theManualFormIsPrefilledAndExplained() throws Exception {
        mockMvc.perform(get("/api/catalogue/countries/LB/manual-form"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].question")
                        .value("About how much do you spend on rent each month?"))
                .andExpect(jsonPath("$[0].suggested").value("350.00"))
                .andExpect(jsonPath("$[0].covers[0]").value("Rent - your rent or mortgage payment"))
                .andExpect(jsonPath("$[0].basis").value(containsString("starting point")))
                // Eleven blank boxes is where people abandon signup, so nothing arrives empty.
                .andExpect(jsonPath("$[*].suggested").value(everyItem(not(is("0.00")))))
                .andExpect(content().string(not(containsString("ESTIMATED"))));
    }
}
