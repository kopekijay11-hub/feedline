package ca.feedline.config;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SourceRegistryInitializer implements ApplicationRunner {

    private final FeedProperties config;
    private final FeedSourceRepository feedSources;

    public SourceRegistryInitializer(FeedProperties config, FeedSourceRepository feedSources) {
        this.config = config;
        this.feedSources = feedSources;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (config.getSources() == null) {
            return;
        }
        for (FeedProperties.Source sourceConfig : config.getSources()) {
            if (sourceConfig.getUrl() == null || sourceConfig.getUrl().isBlank()) {
                continue;
            }
            try {
                Optional<FeedSource> existing = feedSources.findByUrl(sourceConfig.getUrl());
                if (existing != null && existing.isPresent()) {
                    continue;
                }
                String name = sourceConfig.getName();
                if (name == null || name.isBlank()) {
                    name = sourceConfig.getUrl();
                }
                FeedSource source = new FeedSource(name, sourceConfig.getUrl());
                source.setEnabled(sourceConfig.isEnabled());
                feedSources.save(source);
            } catch (Exception ignored) {
                // Best-effort registry sync; polling/health still work against rows created later.
            }
        }
    }
}
