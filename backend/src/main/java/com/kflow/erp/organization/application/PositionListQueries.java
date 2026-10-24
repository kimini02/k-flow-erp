package com.kflow.erp.organization.application;
import java.util.UUID;
/** Internal list contract; deliberately outside organization::api. */
public interface PositionListQueries {
    PositionPage listPositions(UUID companyId, String status, int pageIndex, Integer pageSize);
}
