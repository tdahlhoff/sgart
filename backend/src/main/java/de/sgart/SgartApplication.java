package de.sgart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the SGART modular monolith.
 *
 * <p>Bounded contexts live in sibling packages ({@code collaboration}, {@code storereference},
 * {@code identity}, {@code priceintelligence}) plus the {@code shared} kernel. Each business
 * context is split into the hexagonal layers {@code domain / application / adapter/in /
 * adapter/out} (AD-1, AD-2). This class is deliberately the only Spring bootstrap type; framework
 * concerns never reach a {@code domain} package.
 *
 * <p>{@code @EnableScheduling} (Story 7.1, F4) backs the account-retention sweep's {@code
 * @Scheduled} trigger ({@code identity.adapter.in.ScheduledAccountRetentionSweep}) — the first
 * scheduled job in the codebase.
 */
@SpringBootApplication
@EnableScheduling
public class SgartApplication {

    public static void main(String[] args) {
        SpringApplication.run(SgartApplication.class, args);
    }
}
