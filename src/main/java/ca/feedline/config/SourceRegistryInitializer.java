package ca.feedline.config;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SourceRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistryInitializer.class);

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
                String name = sourceConfig.getName();
                if (name == null || name.isBlank()) {
                    name = sourceConfig.getUrl();
                }
                Optional<FeedSource> existing = feedSources.findByUrl(sourceConfig.getUrl());
                if (existing != null && existing.isPresent()) {
                    FeedSource source = existing.get();
                    boolean changed = false;
                    if (!name.equals(source.getName())) {
                        source.setName(name);
                        changed = true;
                    }
                    if (source.isEnabled() != sourceConfig.isEnabled()) {
                        source.setEnabled(sourceConfig.isEnabled());
                        changed = true;
                    }
                    if (changed) {
                        feedSources.save(source);
                        log.info("Synced feed source {} (id={}) with configured metadata",
                                source.getUrl(), source.getId());
                    }
                    continue;
                }
                FeedSource source = new FeedSource(name, sourceConfig.getUrl());
                source.setEnabled(sourceConfig.isEnabled());
                feedSources.save(source);
                log.info("Registered feed source {} (id={})", source.getUrl(), source.getId());
            } catch (Exception e) {
                log.warn("Failed to sync feed source {} with registry: {}",
                        sourceConfig.getUrl(), e.getMessage());
            }
        }
    }
}
