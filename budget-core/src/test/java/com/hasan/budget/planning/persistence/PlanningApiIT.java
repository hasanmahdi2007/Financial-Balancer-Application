package com.hasan.budget.planning.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hasan.budget.web.CurrentUserArgumentResolver;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The API over the real schema: the whole happy path, and the two failures that would end the
 * product if they ever passed.
 *
 * <p>An integration test rather than a unit one because what can actually be wrong here is the SQL,
 * the migration and the serialised shape. The service's scoping is covered in the fast tier; this is
 * where the promise that the queries keep it is checked against a database that would happily return
 * another user's rows if a WHERE clause were dropped.
 */
@AutoConfigureMockMvc
class PlanningApiIT extends PlanningDatabaseFixture {

    // Fresh ids per test method: these tests share one database, and a test that only passes when
    // it runs first is worth less than no test at all.
    private final String ana = "ana-" + UUID.randomUUID();
    private final String ben = "ben-" + UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("the whole path, from signing up to a plan with tradeoffs")
    void theHappyPathProducesAPlanThatExplainsItself() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, post("/api/v1/goals"))
                        .content("""
                                {"name":"Car","target":"12000.00","deadline":"2027-06-30","priority":"high"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goal.name").value("Car"))
                .andExpect(jsonPath("$.goal.priority.label").value("Very important"))
                .andExpect(jsonPath("$.plan.goals[0].fromBalance").value("12000.00"))
                .andExpect(jsonPath("$.plan.goals[0].monthlyNeeded").value("0.00"))
                .andExpect(jsonPath("$.plan.goals[0].status.label").value("Already covered"));

        mockMvc.perform(as(ana, post("/api/v1/plan")))
                .andExpect(status().isCreated())
                // The two figures that must always travel together: a monthly surplus is not money in
                // hand, and cuts shown without the reduction already assumed read as the whole job.
                .andExpect(jsonPath("$.surplus.amount").exists())
                .andExpect(jsonPath("$.surplus.assumedReduction").exists())
                .andExpect(jsonPath("$.cuts.alreadyAssumed").exists())
                .andExpect(jsonPath("$.cuts.totalChange").exists())
                .andExpect(jsonPath("$.money.runway.label").exists())
                // Nothing internal reaches the payload, exactly as the catalogue promises.
                .andExpect(content().string(not(containsString("DINING_OUT"))))
                .andExpect(content().string(not(containsString("CROWDSOURCED"))))
                .andExpect(content().string(not(containsString("AT_RISK"))))
                .andExpect(content().string(not(containsString("ESTIMATED"))));

        mockMvc.perform(as(ana, get("/api/v1/plan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("You asked for a fresh plan"));

        mockMvc.perform(as(ana, get("/api/v1/plan/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].changes").doesNotExist())
                .andExpect(jsonPath("$[0].changes.surplus.before").exists());
    }

    /**
     * The single most valuable test in the API surface, over HTTP and over the real schema. One user
     * reading another's plan is the failure that ends the product.
     */
    @Test
    @DisplayName("one user cannot read another user's plan, their goals or their money")
    void oneUserCannotReadAnotherUsersPlan() throws Exception {
        onboard(ana);
        String planId = jdbc.sql("SELECT id FROM plan_snapshot WHERE user_id = :user")
                .param("user", ana)
                .query(String.class)
                .optional()
                .orElseGet(() -> {
                    throw new AssertionError("onboarding should have produced a plan to try to steal");
                });

        mockMvc.perform(as(ben, get("/api/v1/plan"))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, get("/api/v1/plan/history/" + planId)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("no plan")));
        mockMvc.perform(as(ben, get("/api/v1/plan/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(ben, get("/api/v1/goals")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(ben, get("/api/v1/money"))).andExpect(status().isNotFound());
        mockMvc.perform(as(ben, get("/api/v1/profile"))).andExpect(status().isNotFound());

        // And Ana still has everything: a refused request must not half-happen.
        mockMvc.perform(as(ana, get("/api/v1/plan"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a request with no user injected is refused before anything is read")
    void aRequestWithNoInjectedUserIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/plan"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Sign in to see this."))
                // The path that failed, so a problem can be traced back to the request that caused it.
                .andExpect(jsonPath("$.instance").value("/api/v1/plan"));

        // The catalogue is reference data and stays reachable, which is what the gateway's own health
        // and the signup flow depend on.
        mockMvc.perform(get("/api/catalogue/countries")).andExpect(status().isOk());
    }

    /**
     * Append-only is enforced by the database, not only by the absence of a method. A snapshot is the
     * record of what a user was told; rewriting one would make the history a record of today's
     * opinion about the past.
     */
    @Test
    @DisplayName("a stored plan cannot be changed or deleted, even directly")
    void snapshotsCannotBeRewritten() throws Exception {
        onboard(ana);

        assertThatThrownBy(() -> jdbc.sql("UPDATE plan_snapshot SET reason = 'rewritten' WHERE user_id = :user")
                        .param("user", ana)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM plan_snapshot WHERE user_id = :user")
                        .param("user", ana)
                        .update())
                .hasMessageContaining("append-only");

        assertThat(jdbc.sql("SELECT count(*) FROM plan_snapshot WHERE user_id = :user")
                        .param("user", ana)
                        .query(Integer.class)
                        .single())
                .isPositive();
    }

    /**
     * Everything the app asks for has to explain itself, so the questions endpoint is checked for the
     * explanation and not only for the number: a pre-filled figure with no stated basis reads as
     * arbitrary, and a user who thinks a number is arbitrary either ignores it or replaces it at
     * random.
     */
    @Test
    @DisplayName("the questions the app still has to ask explain themselves")
    void everyQuestionSaysWhatItIsAskingAndWhy() throws Exception {
        mockMvc.perform(as(ana, put("/api/v1/profile"))
                        .content("""
                                {"country":"LB","city":"beirut","incomeArrivesTaxed":true}"""))
                .andExpect(status().isOk());

        mockMvc.perform(as(ana, get("/api/v1/questions")))
                .andExpect(status().isOk())
                // Nothing is known yet, so the money questions come first and the rest follow.
                .andExpect(jsonPath("$[?(@.key == 'monthly-income')].why").exists())
                .andExpect(jsonPath("$[?(@.key == 'lifestyle')].choices[0].label").exists())
                .andExpect(jsonPath("$[?(@.key == 'spending:groceries')].suggested").exists())
                .andExpect(jsonPath("$[?(@.key == 'spending:groceries')].covers[0]")
                        .value(hasItem(containsString("Groceries - "))))
                .andExpect(content().string(not(containsString("GROCERIES"))))
                .andExpect(content().string(not(containsString("discretionary"))))
                .andExpect(content().string(not(containsString("floor"))));
    }

    /** A figure the user gives beats ours, and a commitment they name is theirs to lock. */
    @Test
    @DisplayName("the user's own figures and named commitments are kept and shown back")
    void theUsersOwnFiguresAreKept() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, put("/api/v1/overrides/groceries")).content("""
                        {"amount":"275.00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("275.00"))
                .andExpect(jsonPath("$.basis.label").value("Your own figure"));

        mockMvc.perform(as(ana, put("/api/v1/line-items/gym")).content("""
                        {"label":"Gym membership","category":"subscriptions","amount":"30.00",
                         "howWilling":"locked","kind":"on-top"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.howWilling.label").value("Cannot be changed"))
                .andExpect(jsonPath("$.kind.label").value("Extra, on top of my Subscriptions"));

        // A locked line is never proposed as a cut; it gets a sentence instead, and never a number.
        mockMvc.perform(as(ana, post("/api/v1/plan")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hints[?(@.id == 'gym')].hint").exists())
                .andExpect(jsonPath("$.cuts.suggested[?(@.lineIds[0] == 'gym')]").isEmpty());

        mockMvc.perform(as(ana, get("/api/v1/line-items")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(as(ben, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/line-items/gym")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ana, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/line-items/gym")))
                .andExpect(status().isNoContent());
    }

    /**
     * A goal the user nominates to finish first takes the balance ahead of a more important one. The
     * route is also under test: it sits beside {@code PUT /goals/{id}} and must not be read as a goal
     * called "finish-first".
     */
    @Test
    @DisplayName("a nominated goal takes the balance ahead of a more important one")
    void aNominatedGoalIsFundedFirst() throws Exception {
        onboard(ana);
        String car = goalId(post("/api/v1/goals"), """
                {"name":"Car","target":"9000.00","deadline":"2027-06-30","priority":"medium"}""");
        goalId(post("/api/v1/goals"), """
                {"name":"Emergency fund","target":"6000.00","deadline":"2027-06-30","priority":"critical"}""");

        // By importance, the emergency fund would take the $12,000 first.
        mockMvc.perform(as(ana, get("/api/v1/plan")))
                .andExpect(jsonPath("$.goals[?(@.name == 'Emergency fund')].fromBalance").value(hasItem("6000.00")));

        mockMvc.perform(as(ana, put("/api/v1/goals/finish-first")).content("""
                        {"goalId":"%s"}""".formatted(car)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.goals[?(@.id == '%s')].fromBalance".formatted(car))
                        .value(hasItem("9000.00")))
                .andExpect(jsonPath("$.plan.goals[?(@.id == '%s')].finishFirst".formatted(car))
                        .value(hasItem(true)));

        // And it can be taken back, which returns the balance to order of importance.
        mockMvc.perform(as(ana, put("/api/v1/goals/finish-first")).content("""
                        {"goalId":null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.goals[?(@.name == 'Emergency fund')].fromBalance")
                        .value(hasItem("6000.00")));
    }

    /**
     * Lines are named in one flat space - a category key, or an item's own id - so an item calling
     * itself "rent" is refused rather than left to collide with the rent line when money is moved.
     */
    @Test
    @DisplayName("a named commitment cannot take the name of a kind of spending")
    void aNamedItemCannotCollideWithACategory() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, put("/api/v1/line-items/rent")).content("""
                        {"label":"Parking space","category":"rent","amount":"50.00","kind":"on-top"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("already the name of a kind of spending")));
    }

    /** Every refusal is a problem document with a sentence in it, including Spring's own. */
    @Test
    @DisplayName("a request that does not make sense says what would")
    void badRequestsExplainThemselves() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, put("/api/v1/spending")).content("""
                        {"groceries-and-things":"100.00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("groceries")));

        mockMvc.perform(as(ana, get("/api/v1/choices/item-kinds")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(as(ana, post("/api/v1/goals")).content("""
                        {"name":"Car","target":"12000.00","deadline":"next June","priority":"high"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("2027-06-30")));
    }

    @Test
    @DisplayName("the affordability answer names a cheaper option rather than only warning")
    void theAffordabilityAnswerOffersSomethingThatWouldWork() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, post("/api/v1/decisions/afford"))
                        .content("""
                                {"category":"dining-out","band":"fancy","spentThisMonth":"80.00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict.label").exists())
                .andExpect(jsonPath("$.verdict.meaning").exists())
                .andExpect(jsonPath("$.purchase.price").exists())
                .andExpect(jsonPath("$.cheaper.label").exists())
                .andExpect(jsonPath("$.cheaper.verdict.label").exists())
                // Every value on this route carries its own words; not one of them may arrive as a constant.
                .andExpect(content().string(not(containsString("OVER_BUDGET"))))
                .andExpect(content().string(not(containsString("SUSTAINABLE"))))
                .andExpect(content().string(not(containsString("FAST_FOOD"))))
                .andExpect(content().string(not(containsString("ESTIMATED"))))
                .andExpect(content().string(not(containsString("USER_STATED"))));
    }

    @Test
    @DisplayName("rebalancing moves money between lines and names what gave")
    void rebalancingSaysExactlyWhatGaveWay() throws Exception {
        onboard(ana);

        mockMvc.perform(as(ana, post("/api/v1/decisions/rebalance"))
                        .content("""
                                {"raise":"dining-out","amount":"20.00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome.label").exists())
                .andExpect(jsonPath("$.changes[0].label").exists())
                .andExpect(content().string(not(containsString("ABSORBED"))))
                .andExpect(content().string(not(containsString("DISPOSABLE"))))
                .andExpect(content().string(not(containsString("COUNT_MORE_OF_YOUR_BALANCE"))))
                .andExpect(content().string(not(containsString("GIVE_A_GOAL_MORE_TIME"))));
    }

    /** A user with a city, money, spending and one plan already made. */
    private void onboard(String userId) throws Exception {
        mockMvc.perform(as(userId, put("/api/v1/profile"))
                        .content("""
                                {"country":"LB","city":"beirut","lifestyle":"regular","incomeArrivesTaxed":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city.name").value("Beirut"));

        mockMvc.perform(as(userId, put("/api/v1/money"))
                        .content("""
                                {"monthlyIncome":"2000.00","balance":"12000.00"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(as(userId, put("/api/v1/spending"))
                        .content("""
                                {"rent":"600.00","groceries":"320.00","dining-out":"180.00","entertainment":"90.00"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(as(userId, post("/api/v1/plan"))).andExpect(status().isCreated());
    }

    /** Adds a goal and returns the id the API gave it. */
    private String goalId(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mockMvc.perform(as(ana, request).content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.goal.id");
    }

    private static MockHttpServletRequestBuilder as(String userId, MockHttpServletRequestBuilder request) {
        return request.header(CurrentUserArgumentResolver.USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
