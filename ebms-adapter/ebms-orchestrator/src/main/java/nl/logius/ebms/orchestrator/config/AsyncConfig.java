package nl.logius.ebms.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Achtergrond-executor voor asynchrone ebXML Acknowledgments.
 *
 * <p>Begrensde thread-pool (i.p.v. Spring's default {@code SimpleAsyncTaskExecutor}, die
 * onbegrensd threads spawnt) zodat een piek aan async-ACK's geen thread-exhaustion veroorzaakt.
 *
 * @see nl.logius.ebms.orchestrator.service.AckSendingService
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ackTaskExecutor")
    public TaskExecutor ackTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-ack-");
        executor.initialize();
        return executor;
    }
}
