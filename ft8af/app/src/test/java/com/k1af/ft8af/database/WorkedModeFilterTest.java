package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic tests for {@link WorkedModeFilter} — the "same mode only"
 * refinement behind the worked-station scopes. No Android/DB types, so no
 * Robolectric runner is needed.
 */
public class WorkedModeFilterTest {

    @Test
    public void disabledAddsNoPredicate() {
        WorkedModeFilter f = WorkedModeFilter.build(false, "FT8");
        assertThat(f.sqlSuffix).isEmpty();
        assertThat(f.args).isEmpty();
    }

    @Test
    public void enabledAddsUpperCaseModePredicate() {
        WorkedModeFilter f = WorkedModeFilter.build(true, "ft4");
        assertThat(f.sqlSuffix).isEqualTo(" and upper(mode) = ?");
        assertThat(f.args).asList().containsExactly("FT4");
    }

    @Test
    public void enabledWithBlankOrNullModeIsInert() {
        assertThat(WorkedModeFilter.build(true, null).sqlSuffix).isEmpty();
        assertThat(WorkedModeFilter.build(true, "   ").args).isEmpty();
    }

    @Test
    public void withArgsAppendsExtras() {
        WorkedModeFilter f = WorkedModeFilter.build(true, "FT8");
        assertThat(f.withArgs("40m")).asList().containsExactly("40m", "FT8").inOrder();
    }

    @Test
    public void withArgsReturnsBaseUnchangedWhenDisabled() {
        WorkedModeFilter f = WorkedModeFilter.build(false, "FT8");
        assertThat(f.withArgs("40m")).asList().containsExactly("40m");
    }
}
