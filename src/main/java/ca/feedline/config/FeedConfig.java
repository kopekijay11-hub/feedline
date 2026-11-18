package ca.feedline.config;

import ca.feedline.service.FeedFetcher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring {@link ConfigurationProperties} holder for the feed poller settings
 * found under the {@code feed.} prefix in {@code application.yml}.
 */
@Configuration
public class FeedConfig {

    @Bean
    @ConfigurationProperties(prefix = "feed")
    public FeedProperties feedProperties() {
        return new FeedProperties();
    }

    @Bean
    public FeedFetcher feedFetcher() {
        return new FeedFetcher();
    }
}
