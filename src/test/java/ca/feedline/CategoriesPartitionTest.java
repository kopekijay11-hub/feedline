package ca.feedline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CategoriesPartitionTest {

    @Test
    void everyConfiguredSourceAppearsInExactlyOneCategory() throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            root = yaml.load(is);
        }
        Map<String, Object> feed = (Map<String, Object>) root.get("feed");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) feed.get("sources");
        Set<String> configured = new HashSet<>();
        for (Map<String, Object> s : sources) {
            configured.add((String) s.get("name"));
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> categories;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/categories.json")) {
            categories = mapper.readValue(is, new TypeReference<>() {});
        }

        Set<String> mapped = new HashSet<>(categories.keySet());

        assertThat(mapped).containsExactlyInAnyOrderElementsOf(configured);
        assertThat(configured).containsAll(mapped);
        for (String name : mapped) {
            assertThat(categories.get(name)).isNotBlank();
        }
    }
}
