package su.grinev.myvpn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java model for app filter/selection state.
 * No Android dependencies — fully unit-testable.
 */
class AppSelectionModel {

    static class Entry {
        final String packageName;
        final String label;

        Entry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private List<Entry> all = Collections.emptyList();
    private List<Entry> visible = Collections.emptyList();
    private final Set<String> selected;

    AppSelectionModel(Set<String> initiallySelected) {
        this.selected = new HashSet<>(initiallySelected);
    }

    void setEntries(List<Entry> entries) {
        this.all = new ArrayList<>(entries);
        this.visible = new ArrayList<>(entries);
    }

    void filter(String query) {
        if (query == null || query.isEmpty()) {
            visible = new ArrayList<>(all);
            return;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry e : all) {
            if (e.label.toLowerCase(Locale.ROOT).contains(lower)
                    || e.packageName.toLowerCase(Locale.ROOT).contains(lower)) {
                result.add(e);
            }
        }
        visible = result;
    }

    List<Entry> getVisible() {
        return Collections.unmodifiableList(visible);
    }

    boolean isSelected(String packageName) {
        return selected.contains(packageName);
    }

    void setSelected(String packageName, boolean isSelected) {
        if (isSelected) selected.add(packageName);
        else selected.remove(packageName);
    }

    Set<String> getSelectedPackages() {
        return new HashSet<>(selected);
    }

    List<Entry> getSelectedEntries() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : all) {
            if (selected.contains(e.packageName)) result.add(e);
        }
        return Collections.unmodifiableList(result);
    }

    List<Entry> getVisibleUnselected() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : visible) {
            if (!selected.contains(e.packageName)) result.add(e);
        }
        return Collections.unmodifiableList(result);
    }
}
