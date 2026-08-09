package com.adsb.simulation.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * SPA fallback 过滤器：非 API、非静态资源的请求全部转发到 /index.html
 */
@Component
public class SpaFallbackFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // API、静态资源放行
        if (path.startsWith("/api/") || path.startsWith("/h2-console")
                || path.contains(".")) {
            chain.doFilter(request, response);
            return;
        }

        // 其他路径转发到 index.html (SPA 路由)
        request.getRequestDispatcher("/index.html").forward(request, response);
    }
}
