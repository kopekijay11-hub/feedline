package ca.feedline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IngestSkipId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "guid", nullable = false, length = 512)
    private String guid;

    public IngestSkipId() {
    }

    public IngestSkipId(Long sourceId, String guid) {
        this.sourceId = sourceId;
        this.guid = guid;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getGuid() {
        return guid;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IngestSkipId that)) {
            return false;
        }
        return Objects.equals(sourceId, that.sourceId) && Objects.equals(guid, that.guid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, guid);
    }
}
