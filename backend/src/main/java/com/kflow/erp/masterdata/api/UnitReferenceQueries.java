package com.kflow.erp.masterdata.api;

import java.math.BigDecimal;
import java.util.List;

public interface UnitReferenceQueries {
    List<Unit> supportedUnits();
    /** Positive, exact decimal quantity in the same base UOM; never rounds or converts. */
    void requireQuantity(Unit baseUom, BigDecimal quantity);
}
