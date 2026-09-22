package com.hasan.budget.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} from the header the gateway injects, and refuses the request outright
 * when it is absent.
 *
 * <p>This is the defence-in-depth the security chain deliberately leaves out. That chain permits
 * every request, because re-validating the caller's token here would check the same credential twice
 * in the one place a second answer could only disagree with the first. What it cannot do is notice a
 * <em>misconfigured</em> gateway - one routing traffic through without injecting an id - and the
 * failure mode there is silent: anonymous traffic served as though it belonged to someone.
 *
 * <p>So the rule is narrow and lives here: no injected id, no user data. It fails loudly, it costs
 * nothing, and it leaves the catalogue and the health check reachable, which is what
 * {@code CatalogueEndpointIT} asserts.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer model,
            NativeWebRequest request,
            WebDataBinderFactory binders) {

        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new NotSignedInException();
        }
        return userId.strip();
    }

    /** No user was injected, so there is nobody to answer for. Never says which header is missing. */
    public static final class NotSignedInException extends RuntimeException {

        public NotSignedInException() {
            super("Sign in to see this.");
        }
    }
}
