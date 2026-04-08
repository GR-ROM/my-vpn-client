package su.grinev.myvpn;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class AppSelectionModelTest {

    private static final AppSelectionModel.Entry VK =
            new AppSelectionModel.Entry("com.vk.android", "VK");
    private static final AppSelectionModel.Entry SBER =
            new AppSelectionModel.Entry("ru.sberbank.android", "Sberbank");
    private static final AppSelectionModel.Entry YOUTUBE =
            new AppSelectionModel.Entry("com.google.android.youtube", "YouTube");

    private List<AppSelectionModel.Entry> allEntries;

    @Before
    public void setUp() {
        allEntries = Arrays.asList(VK, SBER, YOUTUBE);
    }

    // ---- Initial selection ----

    @Test
    public void noInitialSelection_selectedIsEmpty() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        assertTrue(model.getSelectedPackages().isEmpty());
    }

    @Test
    public void initialSelection_isPreChecked() {
        AppSelectionModel model = new AppSelectionModel(
                new HashSet<>(Collections.singletonList("com.vk.android")));
        assertTrue(model.isSelected("com.vk.android"));
        assertFalse(model.isSelected("ru.sberbank.android"));
    }

    @Test
    public void initialSelection_preservedAfterSetEntries() {
        AppSelectionModel model = new AppSelectionModel(
                new HashSet<>(Collections.singletonList("com.vk.android")));
        model.setEntries(allEntries);
        assertTrue(model.isSelected("com.vk.android"));
    }

    @Test
    public void initialSelection_preservedAfterFilter() {
        AppSelectionModel model = new AppSelectionModel(
                new HashSet<>(Collections.singletonList("com.vk.android")));
        model.setEntries(allEntries);
        model.filter("sber");
        assertTrue(model.isSelected("com.vk.android"));
    }

    // ---- setEntries ----

    @Test
    public void setEntries_populatesVisible() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setEntries(allEntries);
        assertEquals(3, model.getVisible().size());
    }

    @Test
    public void setEntries_emptyList_visibleIsEmpty() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setEntries(Collections.emptyList());
        assertTrue(model.getVisible().isEmpty());
    }

    // ---- filter ----

    @Test
    public void filter_emptyQuery_showsAll() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("sber");
        model.filter("");
        assertEquals(3, model.getVisible().size());
    }

    @Test
    public void filter_nullQuery_showsAll() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("sber");
        model.filter(null);
        assertEquals(3, model.getVisible().size());
    }

    @Test
    public void filter_matchesByLabel() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("sber");
        List<AppSelectionModel.Entry> visible = model.getVisible();
        assertEquals(1, visible.size());
        assertEquals("ru.sberbank.android", visible.get(0).packageName);
    }

    @Test
    public void filter_matchesByPackageName() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("vk.android");
        List<AppSelectionModel.Entry> visible = model.getVisible();
        assertEquals(1, visible.size());
        assertEquals("com.vk.android", visible.get(0).packageName);
    }

    @Test
    public void filter_caseInsensitive() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("YOUTUBE");
        List<AppSelectionModel.Entry> visible = model.getVisible();
        assertEquals(1, visible.size());
        assertEquals("com.google.android.youtube", visible.get(0).packageName);
    }

    @Test
    public void filter_noMatch_returnsEmpty() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("zzznomatch");
        assertTrue(model.getVisible().isEmpty());
    }

    @Test
    public void filter_partialMatch_returnsSubset() {
        AppSelectionModel model = modelWithAllEntries();
        // "android" is in all three package names
        model.filter("android");
        assertEquals(3, model.getVisible().size());
    }

    // ---- selection toggle ----

    @Test
    public void setSelected_true_addsToSelection() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setSelected("com.vk.android", true);
        assertTrue(model.isSelected("com.vk.android"));
    }

    @Test
    public void setSelected_false_removesFromSelection() {
        AppSelectionModel model = new AppSelectionModel(
                new HashSet<>(Collections.singletonList("com.vk.android")));
        model.setSelected("com.vk.android", false);
        assertFalse(model.isSelected("com.vk.android"));
    }

    @Test
    public void setSelected_false_unknownPackage_noError() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setSelected("com.unknown.app", false);
        assertFalse(model.isSelected("com.unknown.app"));
    }

    // ---- getSelectedPackages ----

    @Test
    public void getSelectedPackages_returnsDefensiveCopy() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setSelected("com.vk.android", true);
        Set<String> copy = model.getSelectedPackages();
        copy.add("com.should.not.persist");
        assertFalse(model.isSelected("com.should.not.persist"));
    }

    @Test
    public void getSelectedPackages_reflectsAllToggles() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setSelected("com.vk.android", true);
        model.setSelected("ru.sberbank.android", true);
        model.setSelected("com.vk.android", false);
        Set<String> selected = model.getSelectedPackages();
        assertEquals(1, selected.size());
        assertTrue(selected.contains("ru.sberbank.android"));
    }

    // ---- getVisible is unmodifiable ----

    @Test(expected = UnsupportedOperationException.class)
    public void getVisible_returnsUnmodifiableList() {
        AppSelectionModel model = modelWithAllEntries();
        model.getVisible().add(new AppSelectionModel.Entry("x", "x"));
    }

    // ---- getSelectedEntries ----

    @Test
    public void getSelectedEntries_emptyWhenNoneSelected() {
        AppSelectionModel model = modelWithAllEntries();
        assertTrue(model.getSelectedEntries().isEmpty());
    }

    @Test
    public void getSelectedEntries_returnsOnlySelectedEntries() {
        AppSelectionModel model = modelWithAllEntries();
        model.setSelected("com.vk.android", true);
        model.setSelected("ru.sberbank.android", true);
        List<AppSelectionModel.Entry> selected = model.getSelectedEntries();
        assertEquals(2, selected.size());
        assertTrue(selected.stream().anyMatch(e -> e.packageName.equals("com.vk.android")));
        assertTrue(selected.stream().anyMatch(e -> e.packageName.equals("ru.sberbank.android")));
    }

    @Test
    public void getSelectedEntries_updatesAfterRemoval() {
        AppSelectionModel model = modelWithAllEntries();
        model.setSelected("com.vk.android", true);
        model.setSelected("com.vk.android", false);
        assertTrue(model.getSelectedEntries().isEmpty());
    }

    @Test
    public void getSelectedEntries_initialSelectionIncluded() {
        AppSelectionModel model = new AppSelectionModel(
                new HashSet<>(Collections.singletonList("com.vk.android")));
        model.setEntries(allEntries);
        assertEquals(1, model.getSelectedEntries().size());
        assertEquals("com.vk.android", model.getSelectedEntries().get(0).packageName);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getSelectedEntries_returnsUnmodifiableList() {
        AppSelectionModel model = modelWithAllEntries();
        model.getSelectedEntries().add(new AppSelectionModel.Entry("x", "x"));
    }

    // ---- getVisibleUnselected ----

    @Test
    public void getVisibleUnselected_allAppsWhenNoneSelected() {
        AppSelectionModel model = modelWithAllEntries();
        assertEquals(3, model.getVisibleUnselected().size());
    }

    @Test
    public void getVisibleUnselected_excludesSelectedEntries() {
        AppSelectionModel model = modelWithAllEntries();
        model.setSelected("com.vk.android", true);
        List<AppSelectionModel.Entry> unselected = model.getVisibleUnselected();
        assertEquals(2, unselected.size());
        assertTrue(unselected.stream().noneMatch(e -> e.packageName.equals("com.vk.android")));
    }

    @Test
    public void getVisibleUnselected_respectsActiveFilter() {
        AppSelectionModel model = modelWithAllEntries();
        model.filter("sber");
        // sberbank is visible and not selected
        List<AppSelectionModel.Entry> unselected = model.getVisibleUnselected();
        assertEquals(1, unselected.size());
        assertEquals("ru.sberbank.android", unselected.get(0).packageName);
    }

    @Test
    public void getVisibleUnselected_selectedAndMatchingFilterIsExcluded() {
        AppSelectionModel model = modelWithAllEntries();
        model.setSelected("ru.sberbank.android", true);
        model.filter("sber");
        // sberbank matches filter but is selected → not in unselected
        assertTrue(model.getVisibleUnselected().isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getVisibleUnselected_returnsUnmodifiableList() {
        AppSelectionModel model = modelWithAllEntries();
        model.getVisibleUnselected().add(new AppSelectionModel.Entry("x", "x"));
    }

    // ---- helpers ----

    private AppSelectionModel modelWithAllEntries() {
        AppSelectionModel model = new AppSelectionModel(Collections.emptySet());
        model.setEntries(allEntries);
        return model;
    }
}
