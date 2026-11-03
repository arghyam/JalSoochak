package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.telemetry.repository.SupportedLanguageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The language menu is the template's options for the tenant's supported languages, in preference
 * order — with the whole template list as the fallback whenever that would leave the menu empty.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LanguageMenuService")
class LanguageMenuServiceTest {

    private static final int TENANT = 17;
    private static final String SCHEMA = "tenant_as";
    private static final String SCREEN = "LANGUAGE_SELECTION";

    private static final ConversationTemplateService.TemplateOption ENGLISH = option("OPTION_1", 1, "English");
    private static final ConversationTemplateService.TemplateOption HINDI = option("OPTION_2", 2, "Hindi");
    private static final ConversationTemplateService.TemplateOption ODIA = option("OPTION_3", 3, "Odia");

    @Mock
    private ConversationTemplateService templatesService;
    @Mock
    private SupportedLanguageRepository supportedLanguageRepository;
    @Mock
    private LanguageCatalogRepository languageCatalogRepository;

    private LanguageMenuService service;

    @BeforeEach
    void setUp() {
        service = new LanguageMenuService(templatesService, supportedLanguageRepository, languageCatalogRepository);

        lenient().when(languageCatalogRepository.findLanguageIdByAlias("English")).thenReturn(Optional.of(1));
        lenient().when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(2));
        lenient().when(languageCatalogRepository.findLanguageIdByAlias("Odia")).thenReturn(Optional.of(7));
        lenient().when(languageCatalogRepository.findLanguageIdByAlias("Oriya")).thenReturn(Optional.of(7));
        lenient().when(languageCatalogRepository.findLanguageIdByAlias("Punjabi")).thenReturn(Optional.of(11));
    }

    private static ConversationTemplateService.TemplateOption option(String key, int order, String englishLabel) {
        return new ConversationTemplateService.TemplateOption(key, order, Map.of("en", englishLabel));
    }

    private void givenTemplateOptions(ConversationTemplateService.TemplateOption... options) {
        when(templatesService.resolveScreenOptions(TENANT, SCREEN)).thenReturn(List.of(options));
    }

    private void givenSupportedLanguages(String... languages) {
        when(supportedLanguageRepository.findLanguageNames(SCHEMA)).thenReturn(List.of(languages));
    }

    private List<ConversationTemplateService.TemplateOption> menu() {
        return service.resolveOptions(TENANT, SCHEMA).stream().map(LanguageMenuService.MenuOption::option).toList();
    }

    @Test
    void isEmptyWhenTheTemplateHasNoLanguageOptions() {
        givenTemplateOptions();

        assertThat(service.resolveOptions(TENANT, SCHEMA)).isEmpty();
        verifyNoInteractions(supportedLanguageRepository);
    }

    @Test
    void offersEveryOptionWhenTheTenantSupportsNoLanguage() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages();

        assertThat(service.resolveOptions(TENANT, SCHEMA)).containsExactly(
                new LanguageMenuService.MenuOption(ENGLISH, 1),
                new LanguageMenuService.MenuOption(HINDI, 2),
                new LanguageMenuService.MenuOption(ODIA, 3));
    }

    @Test
    void offersOnlyTheSupportedLanguagesInPreferenceOrderKeepingEachOptionsOrderAsItsId() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("Hindi", "English");

        assertThat(service.resolveOptions(TENANT, SCHEMA)).containsExactly(
                new LanguageMenuService.MenuOption(HINDI, 2),
                new LanguageMenuService.MenuOption(ENGLISH, 1));
    }

    @Test
    void matchesASupportedLanguageToAnOptionThroughTheCatalogsAliases() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("Oriya");

        assertThat(menu()).containsExactly(ODIA);
    }

    @Test
    void listsALanguageSupportedUnderTwoNamesOnce() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("Odia", "Oriya", "English");

        assertThat(menu()).containsExactly(ODIA, ENGLISH);
    }

    @Test
    void leavesOffASupportedLanguageWithNoOption() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("English", "Punjabi");

        assertThat(menu()).containsExactly(ENGLISH);
    }

    @Test
    void leavesOffASupportedLanguageThatIsNotInTheCatalog() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("Klingon", "Hindi");

        assertThat(menu()).containsExactly(HINDI);
    }

    @Test
    void offersEveryOptionWhenNoSupportedLanguageHasOne() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        givenSupportedLanguages("Punjabi");

        assertThat(menu()).containsExactly(ENGLISH, HINDI, ODIA);
    }

    @Test
    void offersEveryOptionWhenTheSupportedLanguagesCannotBeRead() {
        givenTemplateOptions(ENGLISH, HINDI, ODIA);
        when(supportedLanguageRepository.findLanguageNames(SCHEMA))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThat(menu()).containsExactly(ENGLISH, HINDI, ODIA);
    }

    @Test
    void keepsTheTemplatePositionAsTheIdOfAnOptionWithoutAPositiveOrder() {
        // Options without a positive order sort first; the id is the position in the whole list,
        // as it was before the menu was filtered, not the position shown.
        ConversationTemplateService.TemplateOption unorderedHindi = option("OPTION_X", 0, "Hindi");
        givenTemplateOptions(unorderedHindi, option("OPTION_1", 4, "English"), option("OPTION_2", 5, "Odia"));
        givenSupportedLanguages("Odia", "Hindi");

        assertThat(service.resolveOptions(TENANT, SCHEMA))
                .extracting(LanguageMenuService.MenuOption::languageId)
                .containsExactly(5, 1);
    }
}
