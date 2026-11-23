package com.gauribrahme.pgcp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts tenant and request id into the logging context for the life of each request. */
@Component
public class MdcFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);
        String tenant = req.getHeader(TENANT_HEADER);
        if (tenant != null) {
            MDC.put("tenantId", tenant.replaceAll("[^a-zA-Z0-9-]", ""));
        }
        res.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
            MDC.remove("tenantId");
        }
    }
}
