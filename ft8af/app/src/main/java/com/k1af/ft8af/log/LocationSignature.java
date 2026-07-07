package com.k1af.ft8af.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A pure, canonical description of a physical operating location, used as the
 * dedup key for the "one Wavelog station_profile per unique location" feature
 * (issue #437).
 *
 * <p>The type holds the user-selectable location fields (grid, state, DXCC,
 * county, city, CQ/ITU zone) plus an optional POTA reference, together with a
 * configurable set of {@link Field}s that decide which of those fields actually
 * participate in the signature. From only the enabled fields (plus the POTA
 * reference whenever one is present) it computes a stable, normalized
 * {@link #signature()} string: two physically-identical locations always yield
 * the exact same string, so it can key a cache or match an existing profile.
 *
 * <p>This class is intentionally free of any Android dependency so it unit-tests
 * without Robolectric. It performs no network or persistence work — callers feed
 * it already-derived field values.
 *
 * <p><b>Dark feature.</b> Nothing in the live upload path constructs or consults
 * a {@code LocationSignature} yet; this is foundation only (guarded by
 * {@code GeneralVariables.perLocationStationEnabled}, default false).
 */
public final class LocationSignature {

    /** The toggleable location fields. The POTA reference is intentionally NOT a
     *  member: per the design it is always included when present, regardless of
     *  which of these are enabled. */
    public enum Field {
        GRIDSQUARE("GRID"),
        STATE("STATE"),
        DXCC("DXCC"),
        COUNTY("COUNTY"),
        CITY("CITY"),
        CQ_ZONE("CQZ"),
        ITU_ZONE("ITUZ");

        /** Stable token used in the canonical signature string. Never change these
         *  without invalidating every persisted signature. */
        final String token;

        Field(String token) {
            this.token = token;
        }
    }

    /** All location fields enabled (the finest-grained default). Unmodifiable so a
     *  caller can't mutate this shared constant; take {@code EnumSet.copyOf(ALL_FIELDS)}
     *  if you need a mutable copy. */
    public static final Set<Field> ALL_FIELDS =
            Collections.unmodifiableSet(EnumSet.allOf(Field.class));

    private final String gridsquare;
    private final String state;
    private final String dxcc;
    private final String county;
    private final String city;
    private final String cqZone;
    private final String ituZone;
    private final String potaRef;
    private final EnumSet<Field> enabledFields;
    /** Canonical signature computed once at construction (the type is immutable) so
     *  the repeated {@link #signature()}/{@link #equals}/{@link #hashCode} calls made
     *  when this is used as a map key don't re-run the regex whitespace collapse. */
    private final String signature;

    private LocationSignature(Builder b) {
        this.gridsquare = b.gridsquare;
        this.state = b.state;
        this.dxcc = b.dxcc;
        this.county = b.county;
        this.city = b.city;
        this.cqZone = b.cqZone;
        this.ituZone = b.ituZone;
        this.potaRef = b.potaRef;
        // Defensive copy so a caller mutating their set can't change our signature.
        this.enabledFields = b.enabledFields.isEmpty()
                ? EnumSet.noneOf(Field.class)
                : EnumSet.copyOf(b.enabledFields);
        this.signature = computeSignature();
    }

    /**
     * Normalizes a single field value: null/blank become {@code null} (the field
     * is omitted from the signature), otherwise the value is trimmed, internal
     * whitespace runs are collapsed to a single space, and it is upper-cased with
     * a fixed locale so casing/spacing differences never split one location into
     * two signatures.
     */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = value.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.toUpperCase(Locale.ROOT);
    }

    private String rawFor(Field f) {
        switch (f) {
            case GRIDSQUARE: return gridsquare;
            case STATE:      return state;
            case DXCC:       return dxcc;
            case COUNTY:     return county;
            case CITY:       return city;
            case CQ_ZONE:    return cqZone;
            case ITU_ZONE:   return ituZone;
            default:         return null;
        }
    }

    /** The configured set of participating fields (unmodifiable snapshot). */
    public Set<Field> enabledFields() {
        return EnumSet.copyOf(enabledFields.isEmpty()
                ? EnumSet.noneOf(Field.class) : enabledFields);
    }

    /** The (normalized) POTA reference participating in this signature, or null. */
    public String potaRef() {
        return normalize(potaRef);
    }

    /**
     * The canonical, stable dedup key. Emits only the enabled, non-empty fields in
     * a fixed order (the {@link Field} enum declaration order), each as
     * {@code TOKEN=VALUE}, joined by {@code |}. The POTA reference — when present —
     * is always appended last as {@code POTA=VALUE}, independent of the enabled
     * set. Two {@code LocationSignature}s describing the same physical location
     * (same enabled config, same values modulo case/whitespace) produce byte-for-
     * byte identical strings; an all-disabled, POTA-less signature is the empty
     * string.
     */
    public String signature() {
        return signature;
    }

    /** Computes the canonical signature string; called once from the constructor. */
    private String computeSignature() {
        List<String> parts = new ArrayList<>();
        // Iterate the enum (not the set) so ordering is always the declaration order
        // regardless of how the caller built their EnumSet.
        for (Field f : Field.values()) {
            if (!enabledFields.contains(f)) {
                continue;
            }
            String v = normalize(rawFor(f));
            if (v != null) {
                parts.add(f.token + "=" + v);
            }
        }
        String pota = normalize(potaRef);
        if (pota != null) {
            parts.add("POTA=" + pota);
        }
        return String.join("|", parts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationSignature)) return false;
        // Equality is defined purely by the canonical signature: same key == same
        // location for dedup purposes, even if disabled fields differ.
        return signature().equals(((LocationSignature) o).signature());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(signature());
    }

    @Override
    public String toString() {
        return "LocationSignature{" + signature() + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder. Enabled fields default to {@link #ALL_FIELDS}. */
    public static final class Builder {
        private String gridsquare;
        private String state;
        private String dxcc;
        private String county;
        private String city;
        private String cqZone;
        private String ituZone;
        private String potaRef;
        private EnumSet<Field> enabledFields = EnumSet.allOf(Field.class);

        public Builder gridsquare(String v) { this.gridsquare = v; return this; }
        public Builder state(String v) { this.state = v; return this; }
        public Builder dxcc(String v) { this.dxcc = v; return this; }
        public Builder county(String v) { this.county = v; return this; }
        public Builder city(String v) { this.city = v; return this; }
        public Builder cqZone(String v) { this.cqZone = v; return this; }
        public Builder ituZone(String v) { this.ituZone = v; return this; }
        public Builder potaRef(String v) { this.potaRef = v; return this; }

        /** Replace the participating-field set (a copy is taken). Null clears it. */
        public Builder enabledFields(Set<Field> fields) {
            this.enabledFields = (fields == null || fields.isEmpty())
                    ? EnumSet.noneOf(Field.class)
                    : EnumSet.copyOf(fields);
            return this;
        }

        public LocationSignature build() {
            return new LocationSignature(this);
        }
    }
}
