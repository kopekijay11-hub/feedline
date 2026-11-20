package ca.feedline.config;

import ca.feedline.service.FeedFetchConfig;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.RobotsTxtParser;
import ca.feedline.service.Sleeper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring configuration for the feed poller.
 *
 * <p>Creates the FeedFetcher with all its dependencies, including the
 * FeedFetchConfig, RobotsTxtParser, Sleeper, and Clock.
 */
@Configuration
@EnableConfigurationProperties(FeedProperties.class)
public class FeedConfig {

    @Bean
    public FeedFetchConfig feedFetchConfig(FeedProperties feedProperties) {
        return new FeedFetchConfig(
                feedProperties.getUserAgentUrl(),
                feedProperties.getConnectTimeout(),
                feedProperties.getReadTimeout(),
                feedProperties.getMaxBodyBytes(),
                feedProperties.getMaxRedirects(),
                feedProperties.getPerHostInterval(),
                feedProperties.getMaxRetries(),
                feedProperties.isSsrfAllowLoopback()
        );
    }

    @Bean
    public Sleeper sleeper() {
        return Sleeper.REAL;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FeedFetcher feedFetcher(FeedFetchConfig config, Sleeper sleeper, Clock clock) {
        // Create robots parser with config
        RobotsTxtParser robotsParser = new RobotsTxtParser(config);

        // Create fetcher with all dependencies
        return new FeedFetcher(config, robotsParser, sleeper, clock);
    }
}
