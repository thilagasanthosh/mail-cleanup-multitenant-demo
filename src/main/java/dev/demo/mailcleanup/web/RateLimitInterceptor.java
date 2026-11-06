package dev.demo.mailcleanup.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.Map;

/** Applies the per-customer token bucket to every /api/tenants/{tenantId}/** call. 429 + Retry-After when empty. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final CustomerRateLimiter limiter;

    public RateLimitInterceptor(CustomerRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String tenantId = vars == null ? null : vars.get("tenantId");
        if (tenantId == null) return true;
        Duration wait = limiter.tryAcquire(tenantId);
        if (wait.isZero()) return true;
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(seconds));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"Rate limit exceeded for customer " + tenantId.replaceAll("[^A-Za-z0-9._@-]", "")
                + "\",\"retryAfterSeconds\":" + seconds + "}");
        return false;
    }
}
