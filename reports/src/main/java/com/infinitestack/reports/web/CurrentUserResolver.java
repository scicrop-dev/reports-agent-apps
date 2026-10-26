package com.infinitestack.reports.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds a {@link CurrentUser} from the X-IS-* headers injected by the host. A request without
 * them only happens outside the host (local development); it gets the configured fallback role,
 * which defaults to USER so that a misconfiguration never grants admin rights.
 */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    static final String USER_ID = "X-IS-User-Id";
    static final String USERNAME = "X-IS-Username";
    static final String ROLE = "X-IS-Role";
    static final String PRINCIPAL_TYPE = "X-IS-Principal-Type";

    @Value("${reports.local.fallback-role:USER}")
    private String fallbackRole;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return fromHeaders(request.getHeader(USER_ID), request.getHeader(USERNAME),
                request.getHeader(ROLE), request.getHeader(PRINCIPAL_TYPE));
    }

    CurrentUser fromHeaders(String userId, String username, String role, String principalType) {
        if (userId == null || userId.isBlank()) {
            return new CurrentUser("local", "local", fallbackRole, "user");
        }
        return new CurrentUser(userId, username, role == null ? "USER" : role.trim().toUpperCase(),
                principalType == null ? "user" : principalType);
    }
}
