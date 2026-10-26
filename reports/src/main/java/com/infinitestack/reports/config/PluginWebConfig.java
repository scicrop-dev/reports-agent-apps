package com.infinitestack.reports.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.infinitestack.reports.web.CurrentUserResolver;

@Configuration
public class PluginWebConfig implements WebMvcConfigurer {

    @Value("${infinitestack.plugin.base-path:/api/plugins/reports}")
    private String pluginBasePath;

    private final CurrentUserResolver currentUserResolver;

    public PluginWebConfig(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(pluginBasePath + "/assets/**")
                .addResourceLocations("classpath:/static/");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}
