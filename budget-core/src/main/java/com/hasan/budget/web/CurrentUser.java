package com.hasan.budget.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The signed-in user's id, taken from the header the gateway injects after it has checked the token.
 *
 * <p>Every user-scoped endpoint takes this rather than reading a header itself, so that "which user is
 * this?" has exactly one answer in the service and no controller can accidentally trust something
 * else - a path variable, say, which a caller chooses.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
