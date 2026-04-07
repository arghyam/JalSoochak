package org.arghyam.jalsoochak.analytics.config;

public final class SwaggerExamples {
    private SwaggerExamples() {}

    public static final String TENANTS_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "tenantId": 1,
                  "stateCode": "MP",
                  "title": "Madhya Pradesh",
                  "countryCode": "IN",
                  "status": 1,
                  "requiredLpcd": 55,
                  "createdAt": "2026-04-01T10:15:30",
                  "updatedAt": "2026-04-01T10:15:30"
                }
              ]
            }
            """;

    public static final String TENANT_DATA_SUCCESS = """
            {
              "success": true,
              "data": {
                "tenantId": 10,
                "stateCode": "MP",
                "childBoundaryCount": 2,
                "boundaryGeoJson": "{\\"type\\":\\"Polygon\\",\\"coordinates\\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}",
                "averageSchemeRegularity": 0.75,
                "readingSubmissionRate": 0.84,
                "averagePerformanceScore": 0.62,
                "childRegions": [
                  {
                    "childLgdId": 110,
                    "childLgdCName": "Child Region",
                    "childLgdTitle": "Child Region Title",
                    "childBoundaryGeoJson": "{\\"type\\":\\"Polygon\\",\\"coordinates\\":[[[0,0],[0.5,0],[0.5,0.5],[0,0.5],[0,0]]]}",
                    "averageSchemeRegularity": 0.78,
                    "readingSubmissionRate": 0.86,
                    "averagePerformanceScore": 0.64
                  }
                ]
              }
            }
            """;

    public static final String SCHEMES_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "schemeId": 1,
                  "tenantId": 10,
                  "schemeName": "Scheme A",
                  "status": 1
                }
              ]
            }
            """;

    public static final String ANOMALY_STATUSES_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "code": 0,
                  "label": "Unresolved"
                },
                {
                  "code": 1,
                  "label": "In-Progress"
                },
                {
                  "code": 2,
                  "label": "Resolved"
                }
              ]
            }
            """;

    public static final String ESCALATION_STATUSES_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "code": 0,
                  "label": "Unresolved"
                },
                {
                  "code": 1,
                  "label": "In-Progress"
                },
                {
                  "code": 2,
                  "label": "Resolved"
                }
              ]
            }
            """;

    public static final String METER_READINGS_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "meterReadingId": 1,
                  "tenantId": 10,
                  "schemeId": 1,
                  "readingDate": "2026-01-01",
                  "readingValue": 123.45
                }
              ]
            }
            """;

    public static final String SCHEME_REGULARITY_AVERAGE_SUCCESS = """
            {
              "success": true,
              "data": {
                "averageRegularity": 0.75
              }
            }
            """;

    public static final String SCHEME_REGULARITY_PERIODIC_SUCCESS = """
            {
              "success": true,
              "data": {
                "lgdId": 101,
                "departmentId": null,
                "schemeCount": 1,
                "scale": "day",
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "periodCount": 0,
                "metrics": [
                  {
                    "periodStartDate": "2026-01-01",
                    "periodEndDate": "2026-01-01",
                    "totalSupplyDays": 1,
                    "totalWaterQuantity": 1500,
                    "averageRegularity": 0.8
                  }
                ]
              }
            }
            """;

    public static final String READING_SUBMISSION_RATE_SUCCESS = """
            {
              "success": true,
              "data": {
                "readingSubmissionRate": 0.84
              }
            }
            """;

    public static final String SCHEMES_STATUS_COUNT_SUCCESS = """
            {
              "success": true,
              "data": {
                "active_schemes_count": 5,
                "inactive_schemes_count": 1
              }
            }
            """;

    public static final String SCHEMES_DASHBOARD_SUCCESS = """
            {
              "success": true,
              "data": {
                "parentLgdId": 101,
                "parentLgdCName": "Parent",
                "parentLgdTitle": "Parent LGD",
                "activeSchemeCount": 1,
                "inactiveSchemeCount": 1,
                "topSchemeCount": 1,
                "topSchemes": [
                  {
                    "schemeId": 1,
                    "schemeName": "Scheme A",
                    "statusCode": 1,
                    "status": "active",
                    "submissionDays": 10,
                    "reportingRate": 0.5,
                    "totalWaterSupplied": 150,
                    "immediateParentLgdId": 100,
                    "immediateParentLgdCName": "Parent",
                    "immediateParentLgdTitle": "Parent LGD"
                  }
                ]
              }
            }
            """;

    public static final String SCHEMES_REGION_REPORT_JSON_SUCCESS = """
            {
              "success": true,
              "data": {
                "parentLgdId": 101,
                "parentLgdCName": "Parent LGD Name",
                "totalSchemeCount": 1,
                "activeSchemeCount": 1,
                "inactiveSchemeCount": 0,
                "schemeCountInResponse": 1,
                "schemes": [
                  {
                    "schemeId": 1,
                    "schemeName": "Scheme A",
                    "statusCode": 1,
                    "status": "active",
                    "supplyDays": 2,
                    "averageRegularity": 0.6667,
                    "submissionDays": 3,
                    "submissionRate": 1.0
                  }
                ]
              }
            }
            """;

    public static final String SCHEME_PERFORMANCE_SUCCESS = """
            {
              "success": true,
              "data": [
                {
                  "schemePerformanceId": 1,
                  "tenantId": 10,
                  "schemeId": 1,
                  "performanceScore": 0.62
                }
              ]
            }
            """;

    public static final String WATER_QUANTITY_REGION_WISE_SUCCESS = """
            {
              "success": true,
              "data": {
                "childRegionCount": 0,
                "childRegions": [
                  {
                    "childLgdId": 110,
                    "childLgdCName": "Child Region",
                    "childLgdTitle": "Child Region Title",
                    "schemeCount": 2,
                    "householdCount": 150,
                    "totalWaterQuantity": 8200,
                    "supplyDaysInEfficientRange": 45
                  }
                ]
              }
            }
            """;

    public static final String WATER_QUANTITY_PERIODIC_SUCCESS = """
            {
              "success": true,
              "data": {
                "periodCount": 0,
                "metrics": [
                  {
                    "periodStartDate": "2026-01-01",
                    "periodEndDate": "2026-01-01",
                    "schemeCount": 2,
                    "householdCount": 150,
                    "averageWaterQuantity": 55.2
                  }
                ]
              }
            }
            """;

    public static final String OUTAGE_REASONS_SUCCESS = """
            {
              "success": true,
              "data": {
                "childRegionCount": 0,
                "outageReasonSchemeCount": {
                  "power_failure": 0
                }
              }
            }
            """;

    public static final String OUTAGE_REASONS_PERIODIC_SUCCESS = """
            {
              "success": true,
              "data": {
                "periodCount": 0,
                "metrics": [
                  {
                    "periodStartDate": "2026-01-01",
                    "periodEndDate": "2026-01-01",
                    "outageReasonSchemeCount": {
                      "power_failure": 1,
                      "motor_burnt": 0
                    }
                  }
                ]
              }
            }
            """;

    public static final String OUTAGE_REASONS_USER_SUCCESS = """
            {
              "success": true,
              "data": {
                "userId": 11,
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "schemeCount": 2,
                "outageReasonSchemeCount": {
                  "draught": 1
                },
                "dailyOutageReasonDistribution": [
                  {
                    "date": "2026-01-01",
                    "outageReasonSchemeCount": {
                      "draught": 1,
                      "no_electricity": 0,
                      "motor_burnt": 0
                    }
                  }
                ]
              }
            }
            """;

    public static final String NON_SUBMISSION_REASONS_SUCCESS = """
            {
              "success": true,
              "data": {
                "childRegionCount": 0,
                "nonSubmissionReasonSchemeCount": {
                  "operator_absent": 0
                }
              }
            }
            """;

    public static final String NON_SUBMISSION_REASONS_USER_SUCCESS = """
            {
              "success": true,
              "data": {
                "userId": 11,
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "schemeCount": 2,
                "nonSubmissionReasonSchemeCount": {
                  "app_issue": 1
                },
                "dailyNonSubmissionReasonDistribution": [
                  {
                    "date": "2026-01-01",
                    "nonSubmissionReasonSchemeCount": {
                      "app_issue": 1
                    }
                  }
                ]
              }
            }
            """;

    public static final String SUBMISSION_STATUS_USER_SUCCESS = """
            {
              "success": true,
              "data": {
                "userId": 11,
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "schemeCount": 2,
                "compliantSubmissionCount": 4,
                "anomalousSubmissionCount": 1,
                "dailySubmissionSchemeDistribution": [
                  {
                    "date": "2026-01-01",
                    "submittedSchemeCount": 1
                  }
                ]
              }
            }
            """;

    public static final String SUBMISSION_STATUS_SUMMARY_SUCCESS = """
            {
              "success": true,
              "data": {
                "schemeCount": 2,
                "compliantSubmissionCount": 5,
                "anomalousSubmissionCount": 0
              }
            }
            """;

    public static final String WATER_SUPPLY_AVERAGE_PER_REGION_SUCCESS = """
            {
              "success": true,
              "data": {
                "schemeCount": 0,
                "childRegionCount": 0,
                "schemes": [
                  {
                    "schemeId": 1,
                    "schemeName": "Scheme A",
                    "householdCount": 150,
                    "averageWaterSupply": 55.2
                  }
                ],
                "childRegions": [
                  {
                    "childLgdId": 110,
                    "childLgdCName": "Child Region",
                    "childLgdTitle": "Child Region Title",
                    "schemeCount": 2,
                    "householdCount": 150,
                    "averageWaterSupply": 55.2
                  }
                ]
              }
            }
            """;

    public static final String NATIONAL_DASHBOARD_SUCCESS = """
            {
              "success": true,
              "data": {
                "stateCount": 0,
                "states": [
                  {
                    "tenantId": 10,
                    "stateCode": "MP",
                    "title": "Madhya Pradesh",
                    "schemeCount": 12,
                    "averageRegularity": 0.74,
                    "readingSubmissionRate": 0.83,
                    "averageWaterSupply": 55.2
                  }
                ],
                "overallOutageReasonDistribution": {}
              }
            }
            """;

    public static final String SCHEME_REGULARITY_PERIODIC_NATIONAL_SUCCESS = """
            {
              "success": true,
              "data": {
                "schemeCount": 0,
                "scale": "day",
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "periodCount": 0,
                "metrics": [
                  {
                    "periodStartDate": "2026-01-01",
                    "periodEndDate": "2026-01-01",
                    "schemeCount": 10,
                    "averageRegularity": 0.74
                  }
                ]
              }
            }
            """;

    public static final String DATE_DIMENSION_POPULATE_SUCCESS = """
            {
              "success": true,
              "data": "Date dimension populated from 2026-01-01 to 2026-01-31"
            }
            """;

    public static final String GENERIC_FAILURE = """
            {
              "success": false,
              "data": null
            }
            """;
}
