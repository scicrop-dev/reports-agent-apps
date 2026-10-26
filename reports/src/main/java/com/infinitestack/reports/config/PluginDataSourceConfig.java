package com.infinitestack.reports.config;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Read-only pool used by Blocks to read catalog data (gold tables). In the IS it points to the
     * same host database as the main pool; it exists so that a Block can never write, whatever SQL
     * it runs, and so that its load is capped separately. For local development it can point to
     * another database (reports.block-datasource.*), e.g. a dev database with real data while the
     * app's own tables live in a local Postgres.
     */
    @Bean(name = "blockDataSource")
    public HikariDataSource blockDataSource(
            @Value("${reports.block-datasource.url:${spring.datasource.url}}") String url,
            @Value("${reports.block-datasource.username:${spring.datasource.username:}}") String username,
            @Value("${reports.block-datasource.password:${spring.datasource.password:}}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(0);
        dataSource.setInitializationFailTimeout(-1);
        dataSource.setPoolName("reports-blocks");
        return dataSource;
    }
}
