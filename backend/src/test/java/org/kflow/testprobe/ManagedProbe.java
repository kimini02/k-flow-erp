package org.kflow.testprobe;

import jakarta.persistence.*;
import java.util.UUID;

/** Test-only managed object outside production entity scanning; not Employee/Assignment. */
@Entity @Table(name = "org03_test_probe", schema = "org03_probe")
public class ManagedProbe {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String label;
    protected ManagedProbe() {}
    public String label() { return label; }
    public void label(String value) { label = value; }
}
