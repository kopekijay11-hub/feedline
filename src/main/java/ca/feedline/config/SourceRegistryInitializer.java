package ca.feedline.config;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SourceRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistryInitializer.class);

    private final FeedProperties config;
    private final FeedSourceRepository feedSources;
    private final AtomicBoolean seedingComplete = new AtomicBoolean(false);

    public SourceRegistryInitializer(FeedProperties config, FeedSourceRepository feedSources) {
        this.config = config;
        this.feedSources = feedSources;
    }

    public boolean isSeedingComplete() {
        return seedingComplete.get();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (config.getSources() == null) {
            seedingComplete.set(true);
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
                int inserted = feedSources.insertIfAbsent(
                        name, sourceConfig.getUrl(), sourceConfig.isEnabled());
                if (inserted > 0) {
                    // New row inserted, fetch it to log the ID
                    Optional<FeedSource> created = feedSources.findByUrl(sourceConfig.getUrl());
                    created.ifPresent(source -> {
                        source.setCadenceClass(sourceConfig.getCadenceClass());
                        log.info("Registered feed source {} (id={})", source.getUrl(), source.getId());
                    });
                } else {
                    // Concurrent insert won the race; fetch existing and sync metadata
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
                        if (!sourceConfig.getCadenceClass().equals(source.getCadenceClass())) {
                            source.setCadenceClass(sourceConfig.getCadenceClass());
                            changed = true;
                        }
                        if (changed) {
                            feedSources.save(source);
                            log.info("Synced feed source {} (id={}) with configured metadata",
                                    source.getUrl(), source.getId());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to sync feed source {} with registry: {}",
                        sourceConfig.getUrl(), e.getMessage());
            }
        }
        seedingComplete.set(true);
    }
}
