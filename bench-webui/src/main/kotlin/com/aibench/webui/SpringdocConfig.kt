package com.aibench.webui

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger / OpenAPI grouping for bench-webui. Auto-discovered from
 * Spring annotations by springdoc; this file just shapes the view so
 * the Swagger UI on /demo only surfaces the public Demo API endpoints
 * by default. The "internal" group covers every other controller
 * (proxy, llm, appmap, jira, github) for users who want the full surface.
 */
@Configuration
class SpringdocConfig {

    @Bean
    fun apiInfo(): OpenAPI = OpenAPI().info(
        Info()
            .title("ai-bench Demo API")
            .version("0.1.0-SNAPSHOT")
            .description("Programmatic access to demo issues + banking-app lifecycle.")
    )

    @Bean
    fun demoApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("demo")
        .displayName("Demo API")
        .pathsToMatch("/api/demo/**")
        .build()

    @Bean
    fun internalApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("internal")
        .displayName("Internal (proxy / llm / appmap / git)")
        .pathsToMatch(
            "/proxy/**", "/llm/**", "/appmap-navie/**",
            "/demo/banking-app/**", "/demo/appmap/**",
            "/api/proxy", "/api/fs/**"
        )
        .build()
}
