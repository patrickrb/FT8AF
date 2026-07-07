package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.EnumSet;

/**
 * Pure unit tests for {@link LocationSignature}: canonical signature computation,
 * field-subset control, POTA handling, normalization stability, and equality.
 * No Android types, so a bare JUnit runner suffices.
 */
public class LocationSignatureTest {

    private LocationSignature.Builder full() {
        return LocationSignature.builder()
                .gridsquare("FN42")
                .state("NY")
                .dxcc("291")
                .county("Albany")
                .city("Albany")
                .cqZone("5")
                .ituZone("8");
    }

    @Test
    public void signature_allFields_fixedOrderAndTokens() {
        assertThat(full().build().signature())
                .isEqualTo("GRID=FN42|STATE=NY|DXCC=291|COUNTY=ALBANY|CITY=ALBANY|CQZ=5|ITUZ=8");
    }

    @Test
    public void signature_onlyEnabledFieldsParticipate() {
        String sig = full()
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .build()
                .signature();
        assertThat(sig).isEqualTo("GRID=FN42");
    }

    @Test
    public void signature_emptyWhenNoFieldsEnabledAndNoPota() {
        assertThat(full().enabledFields(EnumSet.noneOf(LocationSignature.Field.class))
                .build().signature()).isEmpty();
    }

    @Test
    public void signature_potaAlwaysAppendedEvenWithNoEnabledFields() {
        String sig = full()
                .enabledFields(EnumSet.noneOf(LocationSignature.Field.class))
                .potaRef("k-1234")
                .build()
                .signature();
        assertThat(sig).isEqualTo("POTA=K-1234");
    }

    @Test
    public void signature_potaAppendedLast() {
        String sig = LocationSignature.builder()
                .gridsquare("FN42")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .potaRef("K-1234")
                .build()
                .signature();
        assertThat(sig).isEqualTo("GRID=FN42|POTA=K-1234");
    }

    @Test
    public void signature_skipsNullAndBlankFields() {
        String sig = LocationSignature.builder()
                .gridsquare("FN42")
                .state(null)
                .city("   ")
                .county("")
                .build()
                .signature();
        // Only grid survives; null/blank/whitespace-only are omitted.
        assertThat(sig).isEqualTo("GRID=FN42");
    }

    @Test
    public void normalization_upperCasesTrimsAndCollapsesWhitespace() {
        String sig = LocationSignature.builder()
                .city("  new   york  ")
                .enabledFields(EnumSet.of(LocationSignature.Field.CITY))
                .build()
                .signature();
        assertThat(sig).isEqualTo("CITY=NEW YORK");
    }

    @Test
    public void sameLocation_differentCasingAndSpacing_yieldsIdenticalSignature() {
        LocationSignature a = LocationSignature.builder()
                .gridsquare("fn42").city("albany")
                .enabledFields(EnumSet.of(
                        LocationSignature.Field.GRIDSQUARE, LocationSignature.Field.CITY))
                .build();
        LocationSignature b = LocationSignature.builder()
                .gridsquare(" FN42 ").city(" Albany ")
                .enabledFields(EnumSet.of(
                        LocationSignature.Field.GRIDSQUARE, LocationSignature.Field.CITY))
                .build();
        assertThat(a.signature()).isEqualTo(b.signature());
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    public void equality_definedBySignatureNotByDisabledFields() {
        // Different county values but county disabled in both -> same signature -> equal.
        LocationSignature a = LocationSignature.builder()
                .gridsquare("FN42").county("Albany")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .build();
        LocationSignature b = LocationSignature.builder()
                .gridsquare("FN42").county("Rensselaer")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .build();
        assertThat(a).isEqualTo(b);
    }

    @Test
    public void differentPota_producesDifferentSignatures() {
        LocationSignature a = LocationSignature.builder()
                .gridsquare("FN42")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .potaRef("K-1234").build();
        LocationSignature b = LocationSignature.builder()
                .gridsquare("FN42")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .potaRef("K-5678").build();
        assertThat(a.signature()).isNotEqualTo(b.signature());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    public void builderEnabledSet_isDefensivelyCopied() {
        EnumSet<LocationSignature.Field> fields =
                EnumSet.of(LocationSignature.Field.GRIDSQUARE);
        LocationSignature sig = full().enabledFields(fields).build();
        // Mutating the caller's set after build must not change the signature.
        fields.add(LocationSignature.Field.STATE);
        assertThat(sig.signature()).isEqualTo("GRID=FN42");
    }

    @Test
    public void normalize_helper_handlesNullBlankAndValue() {
        assertThat(LocationSignature.normalize(null)).isNull();
        assertThat(LocationSignature.normalize("   ")).isNull();
        assertThat(LocationSignature.normalize(" ab  cd ")).isEqualTo("AB CD");
    }
}
