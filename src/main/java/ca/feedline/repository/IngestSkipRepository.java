package ca.feedline.repository;

import ca.feedline.domain.IngestSkip;
import ca.feedline.domain.IngestSkipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestSkipRepository extends JpaRepository<IngestSkip, IngestSkipId> {

    @Query("select count(s) from IngestSkip s where s.id.sourceId = :sourceId")
    long countBySourceId(@Param("sourceId") Long sourceId);

    @Query("select coalesce(sum(s.occurrences), 0) from IngestSkip s where s.id.sourceId = :sourceId")
    long sumOccurrencesBySourceId(@Param("sourceId") Long sourceId);
}
