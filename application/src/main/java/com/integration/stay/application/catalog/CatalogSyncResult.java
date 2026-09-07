package com.integration.stay.application.catalog;

/** 한 공급사 스냅샷 적용 결과. 텔레메트리용이며 DB 에 저장하지 않는다. */
public record CatalogSyncResult(
        int propertiesApplied, int roomTypesApplied, int propertiesDeactivated, int roomTypesDeactivated) {}
