package com.kflow.erp.masterdata.api;

/** Approved fixed catalog. No conversion or arbitrary Unit registration is supported. */
public enum Unit {
    EA(0), SET(0), BOX(0), KG(3);
    private final int quantityScale;
    Unit(int quantityScale) { this.quantityScale = quantityScale; }
    public int quantityScale() { return quantityScale; }
}
