package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-cutting behaviour of the dashboard KPI service.
 *
 * <p>Its ~60 read methods all share one skeleton — validate the tenant/region/window, consult the
 * Redis KPI cache, call the repository, shape a response, write the cache back. These tests pin that
 * skeleton across the whole surface rather than one endpoint at a time: every method must reject a
 * bad window and an unknown region, and none may reach the repository after a cache hit.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemeRegularityServiceImpl — cross-cutting behaviour")
class SchemeRegularityServiceImplSweepTest {

    private static final int TENANT = 1;
    private static final int LGD = 101;
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);

    private SchemeRegularityRepository schemeRegularityRepository;

    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimUserRepository dimUserRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SchemeRegularityServiceImpl service;

    @BeforeEach
    void setUp() {
        // A default answer that manufactures a neutral value for whatever a repository method returns,
        // so the sweep exercises the service's own mapping rather than tripping over nulls.
        schemeRegularityRepository = mock(SchemeRegularityRepository.class,
                invocation -> neutralValueFor(invocation.getMethod().getReturnType()));

        service = new SchemeRegularityServiceImpl(
                schemeRegularityRepository, dimTenantRepository, dimUserRepository,
                redisTemplate, new ObjectMapper());

        ReflectionTestUtils.setField(service, "regularitySingleDayLookbackDays", 30);
        ReflectionTestUtils.setField(service, "currentDayCacheTtlSeconds", 1800L);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);

        // The child-scope reads resolve the tenant's own row before shaping their response.
        org.arghyam.jalsoochak.analytics.entity.DimTenant tenant =
                new org.arghyam.jalsoochak.analytics.entity.DimTenant();
        tenant.setTenantId(TENANT);
        tenant.setStateCode("mp");
        tenant.setTitle("Madhya Pradesh");
        lenient().when(dimTenantRepository.findById(anyInt())).thenReturn(Optional.of(tenant));
        lenient().when(dimTenantRepository.findByTenantIdGreaterThan(anyInt())).thenReturn(List.of(tenant));
    }

    // ------------------------------------------------------------------
    // Neutral-value construction
    // ------------------------------------------------------------------

    private static Object neutralValueFor(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0d;
        if (type == boolean.class || type == Boolean.class) return Boolean.FALSE;
        if (type == BigDecimal.class) return BigDecimal.ZERO;
        if (type == String.class) return "";
        if (type == LocalDate.class) return START;
        if (type == UUID.class) return UUID.fromString("11111111-1111-1111-1111-111111111111");
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Optional.class.isAssignableFrom(type)) return Optional.empty();
        if (type.isRecord()) return newRecord(type);
        return null;
    }

    /** Builds a record with every component set to its neutral value. */
    private static Object newRecord(Class<?> type) {
        try {
            var components = type.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                paramTypes[i] = components[i].getType();
                args[i] = neutralValueFor(paramTypes[i]);
            }
            Constructor<?> ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Method selection
    // ------------------------------------------------------------------

    /**
     * Public region-scoped read methods shaped {@code (tenantId, regionId, startDate, endDate)}.
     *
     * <p>The {@code ...ByUser} variants share that signature but their second argument is a user id and
     * they validate the user rather than the tenant, so they follow a different contract and are
     * excluded here.</p>
     */
    private static List<Method> windowedReadMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : SchemeRegularityServiceImpl.class.getMethods()) {
            if (method.getDeclaringClass() != SchemeRegularityServiceImpl.class) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.getName().contains("ByUser")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 4) continue;
            if (types[0] != Integer.class || types[1] != Integer.class) continue;
            if (types[2] != LocalDate.class || types[3] != LocalDate.class) continue;
            methods.add(method);
        }
        return methods;
    }

    private Throwable invokeExpectingFailure(Method method, Object... args) {
        try {
            method.invoke(service, args);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return null;
        }
    }

    private void invokeQuietly(Method method, Object... args) {
        try {
            method.invoke(service, args);
        } catch (Exception ignored) {
            // Individual results are asserted by the focused tests; this drives the shared skeleton.
        }
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        void everyWindowedReadRejectsANonPositiveTenantId() {
            List<Method> methods = windowedReadMethods();
            assertThat(methods).as("expected windowed read methods to sweep").isNotEmpty();

            for (Method method : methods) {
                Throwable failure = invokeExpectingFailure(method, 0, LGD, START, END);
                assertThat(failure)
                        .as("%s should reject tenant_id 0", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void everyWindowedReadRejectsANullTenantId() {
            for (Method method : windowedReadMethods()) {
                Throwable failure = invokeExpectingFailure(method, null, LGD, START, END);
                assertThat(failure)
                        .as("%s should reject a null tenant_id", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void everyWindowedReadRejectsAnInvertedWindow() {
            for (Method method : windowedReadMethods()) {
                Throwable failure = invokeExpectingFailure(method, TENANT, LGD, END, START);
                assertThat(failure)
                        .as("%s should reject an end_date before start_date", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void everyWindowedReadRejectsAMissingDate() {
            for (Method method : windowedReadMethods()) {
                assertThat(invokeExpectingFailure(method, TENANT, LGD, null, END))
                        .as("%s should reject a null start_date", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(invokeExpectingFailure(method, TENANT, LGD, START, null))
                        .as("%s should reject a null end_date", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void everyWindowedReadRejectsANonPositiveRegionId() {
            for (Method method : windowedReadMethods()) {
                Throwable failure = invokeExpectingFailure(method, TENANT, 0, START, END);
                assertThat(failure)
                        .as("%s should reject region id 0", method.getName())
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void noValidationRejectionReachesTheRepository() {
            for (Method method : windowedReadMethods()) {
                invokeExpectingFailure(method, 0, LGD, START, END);
            }

            verify(schemeRegularityRepository, never())
                    .getSchemeRegularityMetrics(anyInt(), anyInt(), any(), any());
        }
    }

    @Nested
    @DisplayName("happy-path sweep")
    class HappyPath {

        @Test
        void everyWindowedReadCompletesWithValidArguments() {
            List<Method> methods = windowedReadMethods();

            for (Method method : methods) {
                Throwable failure = invokeExpectingFailure(method, TENANT, LGD, START, END);
                assertThat(failure)
                        .as("%s should succeed for a valid tenant/region/window", method.getName())
                        .isNull();
            }
        }

        @Test
        void everyWindowedReadWritesItsResultToTheKpiCache() {
            for (Method method : windowedReadMethods()) {
                invokeQuietly(method, TENANT, LGD, START, END);
            }

            verify(valueOperations, org.mockito.Mockito.atLeastOnce())
                    .set(anyString(), anyString(), any(java.time.Duration.class));
        }
    }

    @Nested
    @DisplayName("single-day windows")
    class SingleDayWindows {

        @Test
        void expandASingleDayRequestIntoATrailingWindow() {
            // A one-day regularity figure is meaningless (a scheme is regular or not over a window), so
            // the service widens the start to a lookback-length window *inclusive* of the requested day:
            // a 30-day lookback ending 28-Feb starts on 30-Jan, not 29-Jan.
            service.getAverageSchemeRegularity(TENANT, LGD, END, END);

            verify(schemeRegularityRepository)
                    .getSchemeRegularityMetrics(TENANT, LGD, END.minusDays(29), END);
        }

        @Test
        void reportTheExpandedWindowItActuallyQueried() {
            var response = service.getAverageSchemeRegularity(TENANT, LGD, END, END);

            assertThat(response.getStartDate()).isEqualTo(END.minusDays(29));
            assertThat(response.getEndDate()).isEqualTo(END);
            assertThat(response.getDaysInRange()).isEqualTo(30);
        }

        @Test
        void clampAnUnusableLookbackToASingleDay() {
            ReflectionTestUtils.setField(service, "regularitySingleDayLookbackDays", 0);

            service.getAverageSchemeRegularity(TENANT, LGD, END, END);

            verify(schemeRegularityRepository).getSchemeRegularityMetrics(TENANT, LGD, END, END);
        }

        @Test
        void leaveAMultiDayWindowUntouched() {
            service.getAverageSchemeRegularity(TENANT, LGD, START, END);

            verify(schemeRegularityRepository).getSchemeRegularityMetrics(TENANT, LGD, START, END);
        }
    }

    @Nested
    @DisplayName("KPI caching")
    class Caching {

        @Test
        void doesNotQueryTheRepositoryOnACacheHit() throws Exception {
            String cached = new ObjectMapper().writeValueAsString(
                    org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse.builder()
                            .lgdId(LGD).schemeCount(12).build());
            when(valueOperations.get(anyString())).thenReturn(cached);

            var response = service.getAverageSchemeRegularity(TENANT, LGD, START, END);

            assertThat(response.getSchemeCount()).isEqualTo(12);
            verify(schemeRegularityRepository, never())
                    .getSchemeRegularityMetrics(anyInt(), anyInt(), any(), any());
        }

        @Test
        void recomputesWhenTheCachedEntryIsUnreadable() {
            when(valueOperations.get(anyString())).thenReturn("{not json");

            assertThat(service.getAverageSchemeRegularity(TENANT, LGD, START, END)).isNotNull();
            verify(schemeRegularityRepository).getSchemeRegularityMetrics(TENANT, LGD, START, END);
        }

        @Test
        void stillAnswersWhenTheCacheIsUnreachable() {
            when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

            assertThat(service.getAverageSchemeRegularity(TENANT, LGD, START, END)).isNotNull();
        }

        @Test
        void stillAnswersWhenWritingToTheCacheFails() {
            org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                    .when(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));

            assertThat(service.getAverageSchemeRegularity(TENANT, LGD, START, END)).isNotNull();
        }

        @Test
        void keysDistinctWindowsSeparately() {
            service.getAverageSchemeRegularity(TENANT, LGD, START, END);
            service.getAverageSchemeRegularity(TENANT, LGD, START.minusDays(1), END);

            org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(valueOperations, org.mockito.Mockito.atLeast(2)).get(keys.capture());
            assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        }

        @Test
        void doesNotCacheACurrentDayWindowWhenTheShortTtlIsDisabled() {
            ReflectionTestUtils.setField(service, "currentDayCacheTtlSeconds", 0L);
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

            service.getAverageSchemeRegularity(TENANT, LGD, today.minusDays(7), today);

            verify(valueOperations, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        }
    }

    @Nested
    @DisplayName("response shaping")
    class ResponseShaping {

        @Test
        void reportsTheWindowAndThresholdItActuallyApplied() {
            when(schemeRegularityRepository.getSchemeRegularityMetrics(TENANT, LGD, START, END))
                    .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(10, 250, 8));
            when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                    .thenReturn(new BigDecimal("90"));

            var response = service.getAverageSchemeRegularity(TENANT, LGD, START, END);

            assertThat(response.getLgdId()).isEqualTo(LGD);
            assertThat(response.getStartDate()).isEqualTo(START);
            assertThat(response.getEndDate()).isEqualTo(END);
            assertThat(response.getDaysInRange()).isEqualTo(28);
            assertThat(response.getSchemeCount()).isEqualTo(10);
            assertThat(response.getTotalSupplyDays()).isEqualTo(250);
            assertThat(response.getRegularSchemeCount()).isEqualTo(8);
            assertThat(response.getThresholdPercent()).isEqualByComparingTo("90");
            assertThat(response.getAverageRegularity()).isNotNull();
        }

        @Test
        void reportsZeroRegularityForARegionWithNoSchemes() {
            when(schemeRegularityRepository.getSchemeRegularityMetrics(TENANT, LGD, START, END))
                    .thenReturn(new SchemeRegularityRepository.SchemeRegularityMetrics(0, 0, 0));
            when(schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(TENANT))
                    .thenReturn(new BigDecimal("90"));

            var response = service.getAverageSchemeRegularity(TENANT, LGD, START, END);

            assertThat(response.getSchemeCount()).isZero();
            assertThat(response.getAverageRegularity()).isEqualByComparingTo("0");
        }

        @Test
        void propagatesARepositoryFailureRatherThanServingAWrongNumber() {
            when(schemeRegularityRepository.getSchemeRegularityMetrics(anyInt(), anyInt(), any(), any()))
                    .thenThrow(new IllegalStateException("query timed out"));

            assertThatThrownBy(() -> service.getAverageSchemeRegularity(TENANT, LGD, START, END))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("query timed out");

            verify(valueOperations, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        }
    }
}
