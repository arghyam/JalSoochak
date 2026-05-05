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
                "parentLgdLevel": 1,
                "parentDepartmentLevel": null,
                "childBoundaryCount": 2,
                "averageSchemeRegularity": 0.75,
                "readingSubmissionRate": 0.84,
                "childRegions": [
                  {
                    "lgdId": 110,
                    "departmentId": null,
                    "parentLgdId": 101,
                    "parentDepartmentId": null,
                    "lgdLevel": 2,
                    "schemeCount": 2,
                    "title": "Child Region Title",
                    "lgdCode": "C110"
                  }
                ]
              }
            }
            """;

    public static final String TENANT_BOUNDARIES_SUCCESS = """
            {
              "success": true,
              "data": {
                "tenantId": 10,
                "stateCode": "MP",
                "parentLgdLevel": 1,
                "parentDepartmentLevel": null,
                "childBoundaryCount": 2,
                "childRegionCount": 1,
                "parentBoundaryGeoJson": "{\\"type\\":\\"Polygon\\",\\"coordinates\\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}",
                "childRegions": [
                  {
                    "lgdId": 110,
                    "departmentId": null,
                    "parentLgdId": 101,
                    "parentDepartmentId": null,
                    "lgdLevel": 2,
                    "title": "Child Region Title",
                    "lgdCode": "C110",
                    "boundaryGeoJson": "{\\"type\\":\\"Polygon\\",\\"coordinates\\":[[[0,0],[0.5,0],[0.5,0.5],[0,0.5],[0,0]]]}"
                  }
                ]
              }
            }
            """;

    public static final String TENANT_PERFORMANCE_SCORE_SUCCESS = """
            {
              "success": true,
              "data": {
                "tenantId": 10,
                "stateCode": "MP",
                "parentLgdLevel": 1,
                "parentDepartmentLevel": null,
                "averagePerformanceScore": 0.623,
                "childRegions": [
                  {
                    "lgdId": 110,
                    "departmentId": null,
                    "parentLgdId": 101,
                    "parentDepartmentId": null,
                    "lgdLevel": 2,
                    "lgdCode": "C110",
                    "averagePerformanceScore": 0.641
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

    public static final String CRITICAL_SCHEMES_COUNT_SUCCESS = """
            {
              "success": true,
              "data": {
                "criticalSchemeCount": 3,
                "list": false,
                "page": null,
                "limit": null,
                "schemes": [
                  {
                    "schemeId": 101,
                    "schemeName": "Scheme A",
                    "lastSuppliedDate": "2026-04-01"
                  },
                  {
                    "schemeId": 102,
                    "schemeName": "Scheme B",
                    "lastSuppliedDate": null
                  }
                ]
              }
            }
            """;

    public static final String CRITICAL_SCHEMES_LIST_SUCCESS = """
            {
              "success": true,
              "data": {
                "criticalSchemeCount": 3,
                "list": true,
                "page": 1,
                "limit": 2,
                "schemes": [
                  {
                    "schemeId": 101,
                    "schemeName": "Scheme A",
                    "lastSuppliedDate": "2026-04-01"
                  },
                  {
                    "schemeId": 102,
                    "schemeName": "Scheme B",
                    "lastSuppliedDate": null
                  }
                ]
              }
            }
            """;

    public static final String CONTINUOUS_SCHEMES_COUNT_SUCCESS = """
            {
              "success": true,
              "data": {
                "continuousSchemeCount": 5,
                "list": false,
                "page": null,
                "limit": null,
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "daysInRange": 31,
                "schemes": [
                  {
                    "schemeId": 101,
                    "schemeName": "Scheme A"
                  },
                  {
                    "schemeId": 102,
                    "schemeName": "Scheme B"
                  }
                ]
              }
            }
            """;

    public static final String CONTINUOUS_SCHEMES_LIST_SUCCESS = """
            {
              "success": true,
              "data": {
                "continuousSchemeCount": 2,
                "list": true,
                "page": 1,
                "limit": 2,
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "daysInRange": 31,
                "schemes": [
                  {
                    "schemeId": 101,
                    "schemeName": "Scheme A"
                  },
                  {
                    "schemeId": 102,
                    "schemeName": "Scheme B"
                  }
                ]
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
                "parentLgdLevel": 2,
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
                    "immediateParentLgdTitle": "Parent LGD",
                    "immediateParentLgdLevel": 3,
                    "lgdLadder": {
                      "level_1": 10,
                      "level_2": 50,
                      "level_3": 100,
                      "level_4": 101,
                      "level_5": null,
                      "level_6": null
                    },
                    "departmentLadder": {
                      "level_1": 2001,
                      "level_2": 2002,
                      "level_3": null,
                      "level_4": null,
                      "level_5": null,
                      "level_6": null
                    }
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

    public static final String OFFICER_DASHBOARD_TOTALS_SUCCESS = """
            {
              "success": true,
              "data": {
                "totalEscalationCount": 12,
                "totalAnomalyCount": 3,
                "totalMappedSchemeCount": 8,
                "totalWaterSupplied": 145000
              }
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
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "daysInRange": 31,
                "stateWiseQuantityPerformance": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001428,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "schemeCount": 12,
                    "totalHouseholdCount": 1000,
                    "totalAchievedFhtcCount": 900,
                    "totalPlannedFhtcCount": 950,
                    "totalWaterSuppliedLiters": 500000,
                    "avgWaterSupplyPerScheme": 12800.0
                  }
                ],
                "stateWiseRegularity": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001428,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "schemeCount": 12,
                    "totalSupplyDays": 275,
                    "averageRegularity": 0.74
                  }
                ],
                "stateWiseReadingSubmissionRate": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001428,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "schemeCount": 12,
                    "totalSubmissionDays": 310,
                    "readingSubmissionRate": 0.83
                  }
                ],
                "overallOutageReasonDistribution": {}
              }
            }
            """;

    public static final String NATIONAL_DASHBOARD_BOUNDARY_SUCCESS = """
            {
              "success": true,
              "data": {
                "nationalBoundary": {
                  "type": "Polygon",
                  "coordinates": [[[78.1, 22.9], [78.2, 22.9], [78.2, 23.0], [78.1, 22.9]]]
                },
                "stateWiseBoundaries": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001428,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "boundary": {
                      "type": "Polygon",
                      "coordinates": [[[78.1, 22.9], [78.2, 22.9], [78.2, 23.0], [78.1, 22.9]]]
                    }
                  }
                ]
              }
            }
            """;

    public static final String NATIONAL_DASHBOARD_LEVEL2_BOUNDARY_SUCCESS = """
            {
              "success": true,
              "data": {
                "nationalBoundary": {
                  "type": "Polygon",
                  "coordinates": [[[78.1, 22.9], [78.2, 22.9], [78.2, 23.0], [78.1, 22.9]]]
                },
                "lgdLevel2Boundaries": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001429,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "title": "District-1",
                    "boundary": {
                      "type": "Polygon",
                      "coordinates": [[[78.1, 22.9], [78.2, 22.9], [78.2, 23.0], [78.1, 22.9]]]
                    }
                  }
                ]
              }
            }
            """;

    public static final String NATIONAL_DASHBOARD_LEVEL2_METRICS_SUCCESS = """
            {
              "success": true,
              "data": {
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "daysInRange": 31,
                "overallOutageReasonDistribution": {
                  "power_failure": 12,
                  "no_electricity": 4
                },
                "districts": [
                  {
                    "tenantId": 10,
                    "lgdId": 23001429,
                    "tenantStatus": 1,
                    "stateCode": "MP",
                    "stateTitle": "Madhya Pradesh",
                    "districtTitle": "District-1",
                    "schemeCount": 12,
                    "totalHouseholdCount": 1000,
                    "totalAchievedFhtcCount": 900,
                    "totalPlannedFhtcCount": 950,
                    "totalWaterSuppliedLiters": 500000,
                    "avgWaterSupplyPerScheme": 12800.0,
                    "supplyDaysInEfficientRange": 275,
                    "totalSupplyDays": 275,
                    "averageRegularity": 0.74,
                    "totalSubmissionDays": 310,
                    "readingSubmissionRate": 0.83
                  }
                ]
              }
            }
            """;

    public static final String SCHEME_REGULARITY_PERIODIC_NATIONAL_SUCCESS = """
            {
              "success": true,
              "data": {
                "schemeCount": 0,
                "totalAchievedFhtcCount": 900,
                "scale": "day",
                "startDate": "2026-01-01",
                "endDate": "2026-01-31",
                "periodCount": 0,
                "metrics": [
                  {
                    "periodStartDate": "2026-01-01",
                    "periodEndDate": "2026-01-01",
                    "schemeCount": 10,
                    "totalSupplyDays": 275,
                    "totalWaterQuantity": 500000,
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
