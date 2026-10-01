package org.arghyam.jalsoochak.scheme.statesync.port;

import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Port to a state's master-data system. The only adapter today is {@code JjmBrainClient} (Assam);
 * the reconcilers depend on this interface alone, so another state's system plugs in here.
 *
 * <p>Every list method returns the complete list, having paged through the upstream itself.
 * Failures surface as {@link StateMasterDataException}.
 */
public interface StateMasterDataSource {

    List<UpstreamNode> zones();

    List<UpstreamNode> circles();

    List<UpstreamNode> divisions();

    List<UpstreamNode> subdivisions();

    List<UpstreamNode> districts();

    List<UpstreamNode> blocks();

    List<UpstreamNode> panchayats();

    List<UpstreamNode> villages();

    List<UpstreamPerson> users();

    /** Every live (non-archived) scheme, or only those updated at/after {@code updatedSince} when non-null. */
    List<UpstreamScheme> schemes(LocalDateTime updatedSince);

    Optional<UpstreamScheme> schemeByCode(String code);

    Optional<UpstreamScheme> schemeByCentreSchemeId(String centreSchemeId);

    List<String> archivedSchemeCodes();

    List<String> blockedUserCodes();
}
