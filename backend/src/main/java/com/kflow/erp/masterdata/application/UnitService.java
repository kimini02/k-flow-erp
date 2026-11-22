package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.domain.ItemValues;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;

@Service
public class UnitService implements UnitReferenceQueries {
    public List<Unit> supportedUnits() { return List.of(Unit.values()); }
    public void requireQuantity(Unit unit, BigDecimal quantity) { ItemValues.quantity(unit, quantity); }
}
