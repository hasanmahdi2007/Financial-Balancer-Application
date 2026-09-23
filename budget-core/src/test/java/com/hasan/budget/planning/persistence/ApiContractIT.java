package com.hasan.budget.planning.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The committed {@code happy-path.http} against the routes that actually exist.
 *
 * <p>That file is not only documentation: the client packet is built against it, in parallel, and was
 * told not to guess these shapes. A contract naming a route that does not exist costs somebody a day
 * of debugging the wrong end; a route nobody documented gets built twice or not at all. So both
 * directions are checked - every request in the file reaches a handler, and every endpoint this
 * service publishes appears in the file.
 *
 * <p>It cannot check that the response bodies in the comments are accurate. What it can do is fail the
 * moment a path is renamed on one side only, which is how those comments start going stale.
 */
class ApiContractIT extends PlanningDatabaseFixture {

    /** {@code GET {{gateway}}/api/v1/plan} and friends, as the file writes them. */
    private static final Pattern REQUEST =
            Pattern.compile("^(GET|PUT|POST|DELETE)\\s+\\{\\{gateway}}(/\\S*)", Pattern.MULTILINE);

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    // Named, because the actuator publishes a second mapping of its own and this test is about the
    // application's routes.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every request in the committed contract reaches a real endpoint")
    void theContractOnlyNamesEndpointsThatExist() throws IOException {
        List<Request> documented = documentedRequests();
        assertThat(documented).as("the contract file must be readable and full of requests").hasSizeGreaterThan(15);

        List<Route> routes = publishedRoutes();
        List<String> unanswered = documented.stream()
                .filter(request -> routes.stream().noneMatch(route -> route.matches(request)))
                .map(request -> request.method() + " " + request.path())
                .distinct()
                .toList();

        assertThat(unanswered)
                .as("the contract names these, and nothing answers them")
                .isEmpty();
    }

    @Test
    @DisplayName("every endpoint this service publishes is in the committed contract")
    void nothingIsPublishedWithoutBeingDocumented() throws IOException {
        List<Request> documented = documentedRequests();
        List<String> undocumented = publishedRoutes().stream()
                .filter(route -> documented.stream().noneMatch(route::matches))
                .map(route -> route.method() + " " + route.pattern())
                .sorted()
                .toList();

        assertThat(undocumented)
                .as("these are published and the client has no way to know they exist")
                .isEmpty();
    }

    private static List<Request> documentedRequests() throws IOException {
        String contract = Files.readString(Path.of("..", "api-gateway", "happy-path.http"));
        List<Request> requests = new ArrayList<>();
        Matcher found = REQUEST.matcher(contract);
        while (found.find()) {
            // The query string is not part of a route; the path is.
            String path = found.group(2).split("\\?")[0];
            requests.add(new Request(found.group(1), path));
        }
        return requests;
    }

    /** Every route under /api, which is everything a client is served. */
    private List<Route> publishedRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> {
            for (String pattern : patternsOf(mapping)) {
                if (!pattern.startsWith("/api")) {
                    continue;
                }
                mapping.getMethodsCondition().getMethods().stream()
                        .map(Enum::name)
                        .forEach(method -> routes.add(new Route(method, pattern)));
            }
        });
        assertThat(routes).as("the context must actually publish endpoints, or this proves nothing")
                .hasSizeGreaterThan(15);
        return List.copyOf(routes);
    }

    private static Set<String> patternsOf(RequestMappingInfo mapping) {
        return mapping.getPathPatternsCondition() == null
                ? Set.of()
                : mapping.getPathPatternsCondition().getPatternValues();
    }

    private record Request(String method, String path) {}

    private record Route(String method, String pattern) {

        boolean matches(Request request) {
            return method.equals(request.method())
                    && PARSER.parse(pattern).matches(PathContainer.parsePath(request.path()));
        }
    }
}
