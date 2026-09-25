package com.hasan.budget.planning.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hasan.budget.web.CurrentUserArgumentResolver;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A plan belongs to a place (P11), over HTTP and the real schema: the move, the chooser, going back,
 * who may reach which plan, and the migration that filed every existing user's data under one plan.
 */
@AutoConfigureMockMvc
class PlanPerPlaceIT extends PlanningDatabaseFixture {

    private final String ana = "ana-" + UUID.randomUUID();
    private final String ben = "ben-" + UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JsonMapper json;

    @Test
    @DisplayName("moving: refused as an edit, offered as a choice, and the old plan comes back intact")
    void aMoveResumesOrStartsAPlanAndNeverRePricesTheOldOne() throws Exception {
        onboardInBeirut(ana);
        String beirutPlan = only(ana, "LB").get("id").asString();

        // Changing the place of a made plan is a move, and says so in a type the client routes on.
        mockMvc.perform(as(ana, put("/api/v1/profile"))
                        .content("""
                                {"country":"US","city":"austin","lifestyle":"regular","incomeArrivesTaxed":true}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(endsWith("/choose-a-plan")))
                .andExpect(jsonPath("$.detail").value(containsString("Beirut, Lebanon")));

        // Nothing in the US yet: the chooser has only the way to start one.
        mockMvc.perform(as(ana, get("/api/v1/plans").param("country", "US")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(0))
                .andExpect(jsonPath("$.startNew.label").value("Start a new plan here"))
                .andExpect(jsonPath("$.startNew.bringGoalsLabel").exists());

        mockMvc.perform(as(ana, post("/api/v1/plans"))
                        .content("""
                                {"country":"US","city":"austin","bringGoals":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.place.label").value("Austin, United States"))
                .andExpect(jsonPath("$.place.city.id").value("austin"))
                .andExpect(jsonPath("$.summary").doesNotExist());

        // Austin starts clean: no Beirut plan on the dashboard, no Beirut spending, the goal along.
        mockMvc.perform(as(ana, get("/api/v1/plan"))).andExpect(status().isNotFound());
        mockMvc.perform(as(ana, get("/api/v1/plan/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(ana, get("/api/v1/goals")))
                .andExpect(jsonPath("$[0].name").value("Car"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM planning_spending s JOIN planning_active_plan a
                          ON a.user_id = s.user_id AND a.plan_id = s.plan_id WHERE s.user_id = :user
                        """)
                .param("user", ana).query(Long.class).single()).isZero();

        // Back in Lebanon, the Beirut plan is offered with what it last showed.
        mockMvc.perform(as(ana, get("/api/v1/plans").param("country", "LB")))
                .andExpect(jsonPath("$.plans.length()").value(1))
                .andExpect(jsonPath("$.plans[0].id").value(beirutPlan))
                .andExpect(jsonPath("$.plans[0].active").value(false))
                .andExpect(jsonPath("$.plans[0].summary.goals").value(1))
                .andExpect(jsonPath("$.plans[0].use.label").value("Use this plan"));

        mockMvc.perform(as(ana, put("/api/v1/plans/active")).content("{\"planId\":\"" + beirutPlan + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.place.label").value("Beirut, Lebanon"));

        mockMvc.perform(as(ana, get("/api/v1/plan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.place.label").value("Beirut, Lebanon"))
                .andExpect(jsonPath("$.place.country.name").value("Lebanon"));
        mockMvc.perform(as(ana, get("/api/v1/spending"))).andExpect(jsonPath("$.rent").exists());
        mockMvc.perform(as(ana, get("/api/v1/plan/history")))
                .andExpect(jsonPath("$[0].place.label").value("Beirut, Lebanon"));
    }

    @Test
    @DisplayName("one user cannot list, switch to or read another user's plan")
    void oneUserCannotReachAnotherUsersPlan() throws Exception {
        onboardInBeirut(ana);
        String anasPlan = only(ana, "LB").get("id").asString();

        mockMvc.perform(as(ben, get("/api/v1/plans")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(0));
        mockMvc.perform(as(ben, put("/api/v1/plans/active")).content("{\"planId\":\"" + anasPlan + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(ben, get("/api/v1/plan"))).andExpect(status().isNotFound());

        // And the schema refuses it on its own, even to a caller that skipped the check.
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO planning_active_plan (user_id, plan_id) VALUES (:ben, :plan)")
                        .param("ben", ben)
                        .param("plan", anasPlan)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * V34 over data shaped exactly as it was before it, in a schema of its own so it cannot disturb
     * the shared one. This is the only place the backfill runs against rows that already exist - which
     * is what it will meet on every real database - and the only place the one sanctioned write to
     * plan_snapshot is checked to leave its append-only guard switched back on.
     */
    @Test
    @DisplayName("every existing user's data lands in exactly one plan, with history intact")
    void theMigrationFilesEveryExistingUsersDataUnderOnePlan() {
        String schema = "p11_" + UUID.randomUUID().toString().replace("-", "");
        Flyway before = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target("33")
                .load();
        before.migrate();

        JdbcClient old = JdbcClient.create(dataSource);
        old.sql("SET search_path TO " + schema).update();
        old.sql("""
                INSERT INTO %1$s.planning_profile (user_id, country_code, city_slug, lifestyle, income_arrives_taxed)
                VALUES ('existing', 'LB', 'beirut', 'REGULAR', true);
                INSERT INTO %1$s.planning_money (user_id, monthly_income, balance, already_saving)
                VALUES ('existing', 2000.00, 12000.00, 150.00);
                INSERT INTO %1$s.planning_spending (user_id, category, amount) VALUES ('existing', 'RENT', 600.00);
                INSERT INTO %1$s.planning_line_item (user_id, id, label, category, amount, rigidity, scope)
                VALUES ('existing', 'my-gym', 'Gym', 'SUBSCRIPTIONS', 40.00, 'FLEXIBLE', 'ON_TOP');
                INSERT INTO %1$s.planning_goal (user_id, id, name, target, deadline, priority)
                VALUES ('existing', 'g1', 'Car', 9000.00, DATE '2027-03-01', 'HIGH');
                INSERT INTO %1$s.planning_finish_first (user_id, goal_id) VALUES ('existing', 'g1');
                INSERT INTO %1$s.plan_snapshot (id, user_id, taken_at, reason, body)
                VALUES ('s1', 'existing', now(), 'You asked for a fresh plan', '{"id":"s1"}');
                INSERT INTO %1$s.planning_spending (user_id, category, amount) VALUES ('no-place', 'RENT', 1.00);
                """.formatted(schema)).update();

        Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration")
                .target("34").load().migrate();

        String plan = old.sql("SELECT plan_id FROM " + schema + ".planning_active_plan WHERE user_id = 'existing'")
                .query(String.class).single();
        assertThat(old.sql("SELECT count(*) FROM " + schema + ".planning_profile WHERE user_id = 'existing'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(old.sql("SELECT monthly_income FROM " + schema + ".planning_profile WHERE plan_id = :plan")
                .param("plan", plan).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("2000.00");
        assertThat(old.sql("SELECT balance FROM " + schema + ".planning_money WHERE user_id = 'existing'")
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("12000.00");
        for (String table : new String[] {
            "planning_spending", "planning_line_item", "planning_goal", "planning_finish_first", "plan_snapshot"}) {
            assertThat(old.sql("SELECT DISTINCT plan_id FROM " + schema + "." + table + " WHERE user_id = 'existing'")
                            .query(String.class).list())
                    .as(table + " is filed under the user's one plan")
                    .containsExactly(plan);
        }
        assertThat(old.sql("SELECT body::text FROM " + schema + ".plan_snapshot WHERE id = 's1'")
                .query(String.class).single()).as("what the user was told is untouched").contains("\"s1\"");
        assertThat(old.sql("SELECT count(*) FROM " + schema + ".planning_spending WHERE user_id = 'no-place'")
                .query(Long.class).single()).as("a row with no place could never reach a plan").isZero();

        assertThatThrownBy(() -> old.sql("UPDATE " + schema + ".plan_snapshot SET reason = 'rewritten'").update())
                .as("the append-only guard is back on after the backfill")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        old.sql("SET search_path TO public").update();
        old.sql("DROP SCHEMA " + schema + " CASCADE").update();
    }

    private JsonNode only(String userId, String country) throws Exception {
        String body = mockMvc.perform(as(userId, get("/api/v1/plans").param("country", country)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("plans").get(0);
    }

    private void onboardInBeirut(String userId) throws Exception {
        mockMvc.perform(as(userId, put("/api/v1/profile"))
                        .content("""
                                {"country":"LB","city":"beirut","lifestyle":"regular","incomeArrivesTaxed":true}"""))
                .andExpect(status().isOk());
        mockMvc.perform(as(userId, put("/api/v1/money"))
                        .content("""
                                {"monthlyIncome":"2000.00","balance":"12000.00"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(as(userId, put("/api/v1/spending"))
                        .content("""
                                {"rent":"600.00","groceries":"320.00"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(as(userId, post("/api/v1/goals"))
                        .content("""
                                {"name":"Car","target":"9000.00","deadline":"2027-06-30","priority":"high"}"""))
                .andExpect(status().isCreated());
    }

    private static MockHttpServletRequestBuilder as(String userId, MockHttpServletRequestBuilder request) {
        return request.header(CurrentUserArgumentResolver.USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
