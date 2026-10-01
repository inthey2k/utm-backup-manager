package de.fjordkommission.utmbackup.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures infrastructure used exclusively for background backup jobs.
 */
@Configuration(proxyBeanMethods = false)
public class BackupJobConfiguration {

    public static final String BACKUP_TASK_EXECUTOR = "backupTaskExecutor";

    /**
     * Creates a single-threaded executor without a task queue.
     *
     * The bean is not a default candidate so Spring Boot can retain its
     * auto-configured application task executor for framework integrations.
     * BackupJobService provides the authoritative one-job-at-a-time guard.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(BACKUP_TASK_EXECUTOR)
    public Executor backupTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("backup-job-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAcceptTasksAfterContextClose(false);
        executor.initialize();
        return executor;
    }
}
