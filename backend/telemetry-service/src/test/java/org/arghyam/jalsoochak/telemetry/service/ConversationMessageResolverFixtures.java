package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;

import static org.mockito.Mockito.mock;

/** Builds the real chatbot message bundles for service tests that check reply copy. */
public final class ConversationMessageResolverFixtures {

    private ConversationMessageResolverFixtures() {
    }

    /** The real bundles, with languages mapped to locale codes by the built-in list (an empty catalog). */
    public static ConversationMessageResolver real() {
        return new ConversationMessageResolver(new LanguageLocaleResolver(mock(LanguageCatalogRepository.class)));
    }
}
