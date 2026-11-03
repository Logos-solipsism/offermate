package com.offermate.config;

import com.offermate.common.auth.AuthInterceptor;
import com.offermate.modules.agent.auth.AgentAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AgentAuthInterceptor agentAuthInterceptor;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // /api/agent/** 是服务对服务的接口，没有用户登录态，走自己的 AgentAuthInterceptor
                .excludePathPatterns("/api/auth/**", "/api/agent/**", "/doc.html", "/webjars/**", "/v3/api-docs/**");

        // Agent 接口：只认 X-Agent-Token，配置缺失时 fail-closed（一律 401）
        registry.addInterceptor(agentAuthInterceptor)
                .addPathPatterns("/api/agent/**");
    }
}
