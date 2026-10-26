package com.infinitestack.reports.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class PluginDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Small Hikari pool on the host datasource. Configured here, before the pool exists, and not by
     * binding spring.datasource.hikari.*: in the IS the pool is already started when that binding
     * runs, and Hikari refuses changes to a started pool ("The configuration of the pool is sealed
     * once started"), so the app would fail to boot.
     */
    @Bean
    @Primary
    public HikariDataSource dataSource(DataSourceProperties props) {
        HikariDataSource dataSource = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(0);
        dataSource.setPoolName("reports");
        return dataSource;
    }
}
