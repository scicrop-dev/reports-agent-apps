package com.infinitestack.reports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.infinitestack.reports.engine.block.ReportBlock;
import com.infinitestack.reports.support.TestBlocks;

/**
 * Runs the app locally with the test Blocks registered, to try the UI end to end before product
 * Blocks exist: {@code mvn spring-boot:test-run} (needs a Postgres, see AGENTS.md). Test-only:
 * never part of the .ispz.
 */
public class LocalDevApplication {

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoBlocks {
        @Bean ReportBlock testUpper() { return new TestBlocks.Upper(); }
        @Bean ReportBlock testSum() { return new TestBlocks.Sum(); }
        @Bean ReportBlock testArea() { return new TestBlocks.NeedsTable(); }
        @Bean ReportBlock testFailing() { return new TestBlocks.Failing(); }
    }

    public static void main(String[] args) {
        SpringApplication.from(Application::main).with(DemoBlocks.class).run(args);
    }
}
